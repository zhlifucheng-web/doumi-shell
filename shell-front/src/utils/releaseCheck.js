export function normalizeVersion(version) {
  const value = String(version || '').replace(/^v/, '')
  if (!/^\d+\.\d+\.\d+$/.test(value)) throw new Error('版本号格式无效，无法判断是否有更新')
  return value
}

export function hasNewerVersion(version, current) {
  const next = normalizeVersion(version).split('.').map(Number)
  const now = normalizeVersion(current).split('.').map(Number)
  for (let i = 0; i < 3; i++) {
    if (next[i] !== now[i]) return next[i] > now[i]
  }
  return false
}

export async function requestLatestRelease(current, { apiBase = '', refresh = false, fetchImpl = fetch } = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 20000)
  try {
    const response = await fetchImpl(`${apiBase}/api/ssh-console/releases/latest${refresh ? '?refresh=true' : ''}`, {
      cache: 'no-store', signal: controller.signal
    })
    if (!response.ok) throw new Error(`检查更新失败（HTTP ${response.status}），请检查 Doumi Shell 服务或打开 Gitee 下载页面`)
    const payload = await response.json()
    if (payload?.success !== true || payload?.code !== 200)
      throw new Error(payload?.externMessage || payload?.message || '检查更新失败，服务返回了无效数据')
    const result = payload.data
    if (result?.error) throw new Error(result.error)
    const version = normalizeVersion(result?.version)
    const repository = 'https://gitee.com/hahadoumi/web-shell'
    return { version, url: `${repository}/releases/tag/v${version}`, newer: hasNewerVersion(version, current),
      windowsUrl: `${repository}/releases/download/v${version}/Doumi%20Shell-${version}.msi`,
      linuxUrl: `${repository}/releases/download/v${version}/doumi-shell-${version}-linux-x64.tar.gz` }
  } catch (error) {
    if (controller.signal.aborted) throw new Error('检查更新超时，请检查 Doumi Shell 服务与服务器网络，或打开 Gitee 下载页面')
    if (error instanceof TypeError) throw new Error('无法连接更新检查接口，请确认 Doumi Shell 服务可用，或直接打开 Gitee 下载页面')
    if (error instanceof SyntaxError) throw new Error('更新检查接口返回了无效数据，请确认运行中的程序和网页版本一致，或打开 Gitee 下载页面')
    throw error
  } finally { clearTimeout(timer) }
}
