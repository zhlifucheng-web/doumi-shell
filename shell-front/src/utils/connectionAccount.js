import { describeRdpError } from './rdpError.js'

export function accountConnectionForm(initial = {}) {
  return {
    protocol: initial.protocol === 'RDP' ? 'RDP' : 'SSH', id: initial.id ?? null,
    hostId: initial.hostId ?? null, username: initial.username || '',
    authType: initial.authType || 'PASSWORD', defaultCwd: initial.defaultCwd || '',
    name: initial.name || '', domain: initial.domain || '', port: initial.port ?? 3389,
    ignoreCertificate: !!initial.ignoreCertificate, remark: initial.remark || '',
    sortOrder: initial.sortOrder ?? 0, enabled: initial.enabled ?? 1,
    password: '', privateKeyPem: '', passphrase: ''
  }
}

export function accountConnectionPayload(form, { test = false } = {}) {
  const hostId = Number(form.hostId), username = form.username?.trim()
  if (!Number.isSafeInteger(hostId) || hostId < 1) throw new Error('请选择所属主机')
  if (!username || username.length > (form.protocol === 'RDP' ? 255 : 64)) throw new Error('请输入有效的用户名')
  const payload = { ...(form.id ? { id: form.id } : {}), hostId, username }
  if (form.protocol === 'RDP') {
    const port = Number(form.port)
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('RDP 端口必须为 1–65535')
    if (!form.id && !form.password) throw new Error('请输入密码')
    Object.assign(payload, { domain: form.domain?.trim() || '', port, ignoreCertificate: !!form.ignoreCertificate })
    if (form.password) payload.password = form.password
    if (!test) {
      const name = form.name?.trim() || username
      if (name.length > 100) throw new Error('连接名称不能超过 100 字')
      Object.assign(payload, { name })
      // Save identity lives in the URL; the test endpoint accepts it in its body.
      delete payload.id
    }
  } else {
    payload.authType = form.authType || 'PASSWORD'
    if (!['PASSWORD', 'PRIVATE_KEY', 'PRIVATE_KEY_WITH_PASSPHRASE'].includes(payload.authType)) throw new Error('请选择认证方式')
    const credential = payload.authType === 'PASSWORD' ? form.password : form.privateKeyPem
    if (!form.id && !credential) throw new Error('请填写密码或私钥凭据')
    if (credential) {
      payload.secret = payload.authType === 'PASSWORD' ? { password: credential } : { privateKeyPem: credential }
      if (payload.authType === 'PRIVATE_KEY_WITH_PASSPHRASE') payload.secret.passphrase = form.passphrase
    }
    if (!test) payload.defaultCwd = form.defaultCwd || ''
  }
  if (!test) Object.assign(payload, { remark: form.remark || '', sortOrder: Number(form.sortOrder) || 0, enabled: form.enabled ? 1 : 0 })
  return payload
}
const safeConnectionMessages = new Set([
  '远程桌面认证失败，请检查用户名、密码和域',
  '远程桌面证书验证失败，请检查证书或在此连接中选择忽略证书',
  '连接测试超时，请检查主机、端口和网络',
  '无法建立远程桌面连接，请检查主机、端口、凭据和网关',
  '远程桌面连接已关闭，请检查主机和端口',
  '测试连接繁忙，请稍后重试', '远程桌面已禁用', '主机已禁用', '主机不存在', '连接不存在'
])

/** Return only known diagnostic prose; remote errors can echo even unlabelled secrets. */
export function accountConnectionFeedback(value, draft = {}, fallback = '连接失败，请检查设置后重试', { success = false } = {}) {
  let message = typeof value === 'string' ? value.slice(0, 10000).trim() : ''
  const secrets = [draft.password, draft.privateKeyPem, draft.passphrase].filter(value => typeof value === 'string' && value)
  const fragments = secrets.flatMap(secret => [secret, ...secret.split(/\r?\n/).map(line => line.trim()).filter(line => line.length >= 4)])
  for (const secret of [...new Set(fragments)].sort((a, b) => b.length - a.length)) message = message.split(secret).join('[已隐藏]')
  message = message.replace(/\b(?:password|passwd|passphrase|privatekeypem|secret|token|ticket)\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s,;]+)/gi, '[已隐藏]')
    .replace(/\b(?:https?|wss?):\/\/[^\s<>"']+/gi, '[连接地址已隐藏]')
  if (safeConnectionMessages.has(message)) return message
  if (success && /^(?:远程桌面连接成功|连接成功|连接与认证成功(?: \(\d+ms\))?)$/.test(message)) return message
  const diagnostic = describeRdpError(message, draft)
  return diagnostic.kind === 'unknown' ? fallback : diagnostic.message
}
