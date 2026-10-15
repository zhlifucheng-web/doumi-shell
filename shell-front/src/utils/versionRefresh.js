import release from '../../public/version.json'

export const appVersion = release.version

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
