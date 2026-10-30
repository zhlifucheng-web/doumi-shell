export function buildRdpWsUrl(token, origin = window.location.origin) {
  const url = new URL('/ws/ssh-console/rdp', origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.search = new URLSearchParams({ token }).toString()
  return url.toString()
}

export function rdpDimensions(width, height, dpi = 96) {
  const bound = (value, fallback, min, max) => Math.min(max, Math.max(min, Math.round(Number(value)) || fallback))
  return { width: bound(width, 1024, 640, 3840), height: bound(height, 768, 480, 2160), dpi: bound(dpi, 96, 72, 192) }
}

/** Owns one ticket/transport generation. A late request can never reopen a closed tab. */
export function createRdpSession({ openTicket, closeTicket, createTransport, makeUrl = buildRdpWsUrl, onChange = () => {}, scheduleMouse, cancelMouse = () => {} }) {
  let generation = 0, transport = null, token = null, active = false, focused = false, mouseState = null, lastSize = null
  let mouseFrame = null, pendingMouse = null
  const keys = new Set()
  const session = { state: 'disconnected', error: '', connect, disconnect, setActive, setFocused, key, mouse, releaseInput, ctrlAltDelete, resize }
  function update(state, error = '') { session.state = state; session.error = error; onChange({ state, error }) }
  function cancel(ticket) { if (ticket) { try { Promise.resolve(closeTicket(ticket)).catch(() => {}) } catch { /* Best-effort cleanup; gateway also owns timeouts. */ } } }
  function flushMouse() {
    if (mouseFrame !== null) cancelMouse(mouseFrame)
    mouseFrame = null
    const pending = pendingMouse; pendingMouse = null
    if (pending && active && focused) transport?.sendMouse(pending)
  }
  function releaseInput() {
    if (mouseFrame !== null) cancelMouse(mouseFrame)
    mouseFrame = null; pendingMouse = null
    for (const keysym of keys) transport?.sendKey(0, keysym)
    keys.clear()
    if (mouseState && ['left', 'middle', 'right', 'up', 'down'].some(button => mouseState[button])) {
      mouseState = { ...mouseState, left: false, middle: false, right: false, up: false, down: false }
      transport?.sendMouse(mouseState)
    }
  }
  function disconnect() {
    generation++; releaseInput(); focused = false
    const previous = transport; transport = null
    const previousToken = token; token = null
    try { previous?.disconnect() } catch { /* Server cleanup must run even after a socket failure. */ }
    cancel(previousToken); update('disconnected')
  }
  async function connect(request) {
    disconnect(); const current = generation; lastSize = { width: request.width, height: request.height }; update('connecting')
    try {
      const ticket = await openTicket(request)
      if (current !== generation) { cancel(ticket.token); return }
      token = ticket.token
      if (!token) throw new Error('远程桌面会话无效，请重试')
      const callbacks = {
        state: state => {
          if (current !== generation) return
          if (state === 'disconnected') disconnect()
          else update(state)
        },
        error: message => {
          if (current !== generation) return
          disconnect(); update('error', message || '远程桌面连接失败，请检查网关、Windows RDP 和凭据')
        }
      }
      transport = createTransport(makeUrl(token), callbacks)
      transport.connect()
    } catch (error) {
      if (current === generation) { disconnect(); update('error', error.message || '连接失败') }
    }
  }
  function setActive(value) { if (!value) releaseInput(); active = value; if (!value) focused = false }
  function setFocused(value) { if (!value) releaseInput(); focused = value }
  function key(pressed, keysym) {
    if (!active || !focused || !transport) return false
    flushMouse()
    if (pressed) keys.add(keysym); else keys.delete(keysym)
    transport.sendKey(pressed, keysym); return true
  }
  function mouse(state) {
    if (!active || !focused || !transport) return
    const next = { x: state.x, y: state.y, left: !!state.left, middle: !!state.middle, right: !!state.right, up: !!state.up, down: !!state.down }
    const transition = ['left', 'middle', 'right', 'up', 'down'].some(button => next[button] !== !!mouseState?.[button])
    mouseState = next
    // Button/wheel edges are lossless. Only redundant movement within a frame
    // can be replaced, and it must precede the next key or button transition.
    if (transition || !scheduleMouse) { flushMouse(); transport.sendMouse(next) }
    else {
      pendingMouse = next
      if (mouseFrame === null) mouseFrame = scheduleMouse(flushMouse)
    }
  }
  function resize(width, height) {
    if (!active || !transport || session.state !== 'connected') return
    const size = rdpDimensions(width, height)
    if (size.width === lastSize?.width && size.height === lastSize?.height) return
    transport.sendSize(size.width, size.height); lastSize = size
  }
  function ctrlAltDelete() {
    if (!active || !transport || session.state !== 'connected') return
    releaseInput()
    for (const keysym of [65507, 65513, 65535]) transport.sendKey(1, keysym)
    for (const keysym of [65535, 65513, 65507]) transport.sendKey(0, keysym)
  }
  return session
}
