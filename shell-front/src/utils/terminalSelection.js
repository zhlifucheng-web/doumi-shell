// Let xterm's own selection engine handle words, wide characters and drag scrolling,
// even when tmux has enabled mouse reporting for wheel-based history scrolling.
export function bindTerminalSelection(terminal) {
  const element = terminal.element
  const doc = element.ownerDocument
  const win = doc.defaultView
  const isMac = ['Macintosh', 'MacIntel', 'MacPPC', 'Mac68K'].includes(win.navigator.platform)
  const modifier = isMac ? 'altKey' : 'shiftKey'
  const capture = { capture: true }
  let selecting = false
  let contextButton = null

  const modified = event => event.ctrlKey || event.metaKey || event.altKey || event.shiftKey
  const mouseActive = () => terminal.modes.mouseTrackingMode !== 'none'
  const down = event => {
    if (event.button === 2 || (isMac && event.button === 0 && event.ctrlKey && !event.metaKey)) {
      // The app owns the context menu. Its press must not reach tmux and clear
      // text selected for copying; leave the separate contextmenu event alone.
      contextButton = event.button
      event.stopImmediatePropagation()
      return
    }
    if (event.button !== 0 || modified(event) || !mouseActive()) return
    // xterm's public mouse event contract uses Option on Mac and Shift elsewhere
    // to force selection. Only this down event is changed; wheel events stay intact.
    Object.defineProperty(event, modifier, { value: true, configurable: true })
    selecting = true
  }
  const up = event => {
    if (contextButton === event.button || (event.button === 2 && element.contains(event.target))) {
      contextButton = null
      event.stopImmediatePropagation()
      return
    }
    if (!selecting || event.button !== 0) return
    selecting = false
    // A release bubbling through xterm would send a remote mouse report and, in
    // SGR mode, clear the selection. Deliver it only to document selection listeners.
    event.stopImmediatePropagation()
    doc.dispatchEvent(new win.MouseEvent('mouseup', {
      bubbles: false, cancelable: true, view: win,
      clientX: event.clientX, clientY: event.clientY,
      screenX: event.screenX, screenY: event.screenY,
      button: event.button, buttons: event.buttons, detail: event.detail,
      ctrlKey: event.ctrlKey, metaKey: event.metaKey, altKey: event.altKey, shiftKey: event.shiftKey
    }))
  }
  const hover = event => {
    if (!selecting && !event.buttons && !modified(event) && mouseActive()
        && terminal.hasSelection() && element.contains(event.target)) {
      // All-motion reporting must not clear selected text as the pointer moves away.
      event.stopImmediatePropagation()
    }
  }
  element.addEventListener('mousedown', down, capture)
  doc.addEventListener('mouseup', up, capture)
  doc.addEventListener('mousemove', hover, capture)
  return {
    dispose() {
      element.removeEventListener('mousedown', down, capture)
      doc.removeEventListener('mouseup', up, capture)
      doc.removeEventListener('mousemove', hover, capture)
    }
  }
}
