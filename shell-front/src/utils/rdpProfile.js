export function rdpProfileForm(profile = {}) {
  return { name: profile.name || '', username: profile.username || '', domain: profile.domain || '', password: '', port: profile.port ?? 3389, enabled: profile.enabled ?? 1, ignoreCertificate: !!profile.ignoreCertificate }
}

export function rdpProfilePayload(form, hostId, id) {
  const name = form.name?.trim(), username = form.username?.trim(), port = Number(form.port)
  if (!name || name.length > 100) throw new Error('请输入不超过 100 字的连接名称')
  if (!username || username.length > 255) throw new Error('请输入有效的用户名')
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('RDP 端口必须为 1–65535')
  if (!id && !form.password) throw new Error('请输入密码')
  const payload = { hostId, name, username, domain: form.domain?.trim() || '', port, enabled: form.enabled ? 1 : 0, ignoreCertificate: !!form.ignoreCertificate }
  if (form.password) payload.password = form.password
  return payload
}
