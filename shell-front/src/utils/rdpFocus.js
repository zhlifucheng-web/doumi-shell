/** Restores retained DOM focus after a window/tab returns, without stealing it. */
export function bindRdpFocus({ windowTarget, documentTarget, getSurface, isActive, setFocused, releaseInput }) {
  function restoreFocus() {
    const surface = getSurface()
    if (!documentTarget.hidden && isActive() && surface?.contains(documentTarget.activeElement)) setFocused(true)
  }
  function visibilityChanged() { if (documentTarget.hidden) releaseInput(); else restoreFocus() }
  windowTarget.addEventListener('blur', releaseInput)
  windowTarget.addEventListener('focus', restoreFocus)
  documentTarget.addEventListener('visibilitychange', visibilityChanged)
  return () => {
    windowTarget.removeEventListener('blur', releaseInput)
    windowTarget.removeEventListener('focus', restoreFocus)
    documentTarget.removeEventListener('visibilitychange', visibilityChanged)
  }
}
