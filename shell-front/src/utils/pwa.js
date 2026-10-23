export function createPwaInstaller(win, onChange) {
  const displayMode = win.matchMedia('(display-mode: standalone)')
  let deferredPrompt = null
  let disposed = false
  const state = {
    installed: displayMode.matches || win.navigator.standalone === true,
    available: false,
    installing: false
  }
  const publish = () => { if (!disposed) onChange({ ...state }) }
  const beforeInstall = event => {
    if (state.installed) return
    event.preventDefault()
    deferredPrompt = event
    state.available = true
    publish()
  }
  const installed = () => {
    deferredPrompt = null
    state.installed = true
    state.available = false
    publish()
  }
  const modeChanged = () => {
    state.installed = displayMode.matches || win.navigator.standalone === true
    publish()
  }
  win.addEventListener('beforeinstallprompt', beforeInstall)
  win.addEventListener('appinstalled', installed)
  displayMode.addEventListener('change', modeChanged)
  publish()

  return {
    async install() {
      if (disposed || state.installed || state.installing || !deferredPrompt) return false
      const prompt = deferredPrompt
      deferredPrompt = null // Browser installation events can only be used once.
      state.available = false
      state.installing = true
      publish()
      try {
        await prompt.prompt()
        await prompt.userChoice
        return true // The browser handled the choice; appinstalled confirms installation.
      } catch {
        return false
      } finally {
        state.installing = false
        publish()
      }
    },
    dispose() {
      disposed = true
      deferredPrompt = null
      win.removeEventListener('beforeinstallprompt', beforeInstall)
      win.removeEventListener('appinstalled', installed)
      displayMode.removeEventListener('change', modeChanged)
    }
  }
}

export async function registerPwa(win = window) {
  if (!win.isSecureContext || !win.navigator.serviceWorker) return null
  try {
    return await win.navigator.serviceWorker.register('/sw.js', { scope: '/', updateViaCache: 'none' })
  } catch (error) {
    console.warn('PWA registration failed:', error)
    return null
  }
}
