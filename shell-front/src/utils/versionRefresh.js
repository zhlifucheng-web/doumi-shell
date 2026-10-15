import release from '../../public/version.json'

export const appVersion = release.version
export const repositoryUrl = 'https://gitee.com/hahadoumi/web-shell'
export const releaseUrl = `${repositoryUrl}/releases`

let latestVersion = null
let latestReleaseUrl = releaseUrl
const listeners = new Set()

export function hasNewerVersion(version, current = appVersion) {
  const parse = (value) => String(value || '').split('.').map((part) => Number.parseInt(part, 10) || 0)
  const next = parse(version)
  const now = parse(current)
  for (let i = 0; i < Math.max(next.length, now.length); i += 1) {
    if ((next[i] || 0) !== (now[i] || 0)) return (next[i] || 0) > (now[i] || 0)
  }
  return false
}

export async function checkLatestRelease() {
  try {
    const response = await fetch('https://gitee.com/api/v5/repos/hahadoumi/web-shell/releases/latest', {
      cache: 'no-store', signal: AbortSignal.timeout(5000)
    })
    if (!response.ok) return null
    const releaseInfo = await response.json()
    const tag = String(releaseInfo.tag_name || '').replace(/^v/, '')
    if (!tag) return null
    latestVersion = tag
    latestReleaseUrl = releaseInfo.html_url || `${repositoryUrl}/releases`
    listeners.forEach((listener) => listener())
    return { version: tag, url: latestReleaseUrl, newer: hasNewerVersion(tag) }
  } catch {
    return null
  }
}

export function getLatestRelease() {
  return latestVersion ? { version: latestVersion, url: latestReleaseUrl, newer: hasNewerVersion(latestVersion) } : null
}

export function onLatestReleaseChange(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function installVersionRefresh() {
  let checking = false
  async function check() {
    if (checking || document.hidden) return
    checking = true
    try {
      const response = await fetch(`${import.meta.env.BASE_URL}version.json`, { cache: 'no-store', signal: AbortSignal.timeout(5000) })
      if (!response.ok) return
      const { version } = await response.json()
      if (typeof version !== 'string' || version === appVersion) return
      const url = new URL(window.location.href)
      // Avoid a reload loop if an intermediary keeps serving an old page.
      if (url.searchParams.get('v') === version) return
      url.searchParams.set('v', version)
      window.location.replace(url.href)
    } catch { /* The server may be restarting during an upgrade; try again later. */ }
    finally { checking = false }
  }
  window.addEventListener('focus', check)
  document.addEventListener('visibilitychange', check)
  window.addEventListener('vite:preloadError', check)
  const timer = window.setInterval(check, 30000)
  check()
  return () => {
    window.clearInterval(timer)
    window.removeEventListener('focus', check)
    document.removeEventListener('visibilitychange', check)
    window.removeEventListener('vite:preloadError', check)
  }
}
