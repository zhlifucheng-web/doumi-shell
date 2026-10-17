import release from '../../public/version.json'
import { hasNewerVersion as compareVersions, requestLatestRelease } from './releaseCheck'

export const appVersion = release.version
export const repositoryUrl = 'https://gitee.com/hahadoumi/web-shell'
export const releaseUrl = `${repositoryUrl}/releases`

let latestResult = null
let pendingCheck = null
let pendingRefresh = false
const listeners = new Set()

export function hasNewerVersion(version, current = appVersion) {
  return compareVersions(version, current)
}

export function checkLatestRelease({ refresh = false } = {}) {
  if (pendingCheck) {
    if (!refresh || pendingRefresh) return pendingCheck
    // A user-requested check must not reuse a background response served from the short server cache.
    return pendingCheck.then(() => checkLatestRelease({ refresh: true }), () => checkLatestRelease({ refresh: true }))
  }
  pendingRefresh = refresh
  pendingCheck = requestLatestRelease(appVersion, {
    apiBase: (import.meta.env.VITE_API_BASE || '').replace(/\/$/, ''), refresh
  }).then(result => {
    latestResult = result
    listeners.forEach((listener) => listener())
    return result
  }).finally(() => { pendingCheck = null })
  return pendingCheck
}

export function getLatestRelease() {
  return latestResult
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
