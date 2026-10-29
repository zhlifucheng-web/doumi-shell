import test from 'node:test'
import assert from 'node:assert/strict'
import * as vue from 'vue'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'
import { createTerminalConnectionGuard } from '../src/utils/terminalConnectionGuard.js'
import { createHistoryRequest } from '../src/utils/terminalHistory.js'
import { tmuxCompatibility } from '../src/utils/tmuxCompatibility.js'

const filename = new URL('../src/components/ssh/SshTerminal.vue', import.meta.url)

function mountTerminal(props = {}, { keepAliveVisible } = {}) {
  const sockets = [], displays = [], events = [], urls = [], timers = new Set(), frames = new Map(), observers = []
  class TrackedTarget extends EventTarget {
    listeners = new Map()
    addEventListener(type, callback, options) {
      if (!this.listeners.has(type)) this.listeners.set(type, new Set())
      this.listeners.get(type).add(callback); super.addEventListener(type, callback, options)
    }
    removeEventListener(type, callback, options) {
      super.removeEventListener(type, callback, options)
      this.listeners.get(type)?.delete(callback)
      if (!this.listeners.get(type)?.size) this.listeners.delete(type)
    }
  }
  const document = Object.assign(new TrackedTarget(), { visibilityState: 'visible', fonts: new TrackedTarget() })
  const window = Object.assign(new TrackedTarget(), { visualViewport: new TrackedTarget() })
  const layout = { width: 1000, height: 600, shown: true, measured: true }
  let frameId = 0, fits = 0
  const container = {
    get clientWidth() { return layout.shown ? layout.width : 0 },
    get clientHeight() { return layout.shown ? layout.height : 0 },
    getClientRects: () => layout.shown ? [{}] : [], querySelector: () => ({})
  }
  class Socket {
    static CONNECTING = 0
    static OPEN = 1
    constructor(url) { this.url = url; this.readyState = 1; this.sent = []; sockets.push(this) }
    send(data) { this.sent.push(JSON.parse(data)) }
    close() { this.readyState = 3; this.onclose?.() }
    frame(data) { this.onmessage?.({ data: JSON.stringify(data) }) }
  }
  class Display {
    constructor(options) {
      this.cols = options.cols || 80; this.rows = options.rows || 24; this.options = options; this.output = ''; this.disposed = false; this.focuses = 0
      this.buffer = { active: { type: 'normal', baseY: 100, viewportY: 100, cursorY: 10 } }
      this.markers = []; displays.push(this)
    }
    attachCustomKeyEventHandler() {}
    loadAddon(addon) { addon.activate?.(this) }
    open() {}
    onData() { return { dispose() {} } }
    onBinary() { return { dispose() {} } }
    focus() { this.focuses++ }
    resize(cols, rows) { this.cols = cols; this.rows = rows }
    registerMarker(offset) {
      const marker = { line: this.buffer.active.baseY + this.buffer.active.cursorY + offset, isDisposed: false, dispose() { this.isDisposed = true } }
      this.markers.push(marker); return marker
    }
    scrollToLine(line) {
      const next = Math.max(0, Math.min(line, this.buffer.active.baseY))
      if (next !== this.buffer.active.viewportY) this.layoutSynced = true
      this.buffer.active.viewportY = next
    }
    scrollLines(amount) { this.scrollToLine(this.buffer.active.viewportY + amount) }
    scrollToBottom() { this.scrollToLine(this.buffer.active.baseY) }
    refresh() {}
    write(data) { this.output += data }
    dispose() { this.disposed = true }
  }
  const modules = {
    '@/utils/terminalConnectionGuard': { createTerminalConnectionGuard: options => createTerminalConnectionGuard({ ...options,
      setTimer: fn => { timers.add(fn); return fn }, clearTimer: fn => timers.delete(fn) }) },
    '@/utils/terminalInput': { bindTerminalInput: () => {}, encodeTerminalText: text => text },
    '@/utils/terminalSelection': { bindTerminalSelection: () => ({ dispose() {} }) },
    '@/utils/terminalHistory': { createHistoryRequest, TERMINAL_SCROLLBACK: 10000 },
    '@/utils/tmuxCompatibility': { tmuxCompatibility },
    xterm: { Terminal: Display },
    '@xterm/addon-fit': { FitAddon: class {
      activate(display) { this.display = display }
      proposeDimensions() {
        if (!layout.measured) return undefined
        if (!layout.shown || document.visibilityState === 'hidden') return { cols: 2, rows: 1 }
        const scale = (this.display.options.fontSize || 14) / 14
        return { cols: Math.floor(layout.width / (10 * scale)), rows: Math.floor(layout.height / (20 * scale)) }
      }
      fit() { fits++; const size = this.proposeDimensions(); if (size) this.display.resize(size.cols, size.rows) }
    } },
    '@xterm/addon-web-links': { WebLinksAddon: class {} },
    '@xterm/addon-search': { SearchAddon: class {} },
    '@/api/sshConsole': { buildTerminalWsUrl: (id, options) => { urls.push({ id, ...options }); return 'ws://test/terminal' } },
    '@/utils/terminalColorEnhancer': { enhanceTerminalOutput: text => text },
    '@/composables/useBackground': { useBackground: () => ({ background: vue.ref({ enabled: false }) }) },
    '@/composables/useTheme': { useTheme: () => ({ effectiveDark: vue.ref(true) }) },
    '@/composables/useTerminalTheme': { useTerminalTheme: () => ({ activeTheme: vue.ref(null) }) }
  }
  const mounted = mountSfcSetup(filename, {
    modules, keepAliveVisible,
    props: { accountId: 1, terminalId: 'persistent-terminal', encoding: 'UTF-8', active: true, ...props,
      onDisconnected: payload => events.push(['disconnected', payload]),
      onConnecting: () => events.push(['connecting']),
      onConnectionError: payload => events.push(['error', payload]),
      onReady: payload => events.push(['ready', payload]) },
    globals: { WebSocket: Socket, document, window,
      requestAnimationFrame: fn => { const id = ++frameId; frames.set(id, fn); return id },
      cancelAnimationFrame: id => frames.delete(id),
      ResizeObserver: class { constructor(callback) { this.callback = callback; observers.push(this) } observe() {} disconnect() { this.disconnected = true } } },
    onSetup: state => { state.termContainer.value = container }
  })
  return { ...mounted, sockets, displays, events, urls, timers, layout, document, window, frames, observers,
    get fits() { return fits },
    async flushResize() { await vue.nextTick(); await vue.nextTick(); const pending = [...frames.values()]; frames.clear(); pending.forEach(fn => fn()); await vue.nextTick() }
  }
}

test('switching tabs restores each history position after hidden scrolling and background output', async () => {
  const first = mountTerminal(), second = mountTerminal()
  try {
    for (const panel of [first, second]) { panel.sockets[0].frame({ type: 'ready' }); await panel.flushResize() }
    const a = first.displays[0], b = second.displays[0]
    a.buffer.active.viewportY = 25; b.buffer.active.viewportY = 70
    for (let i = 0; i < 3; i++) {
      for (const [panel, display, expected] of [[first, a, 25], [second, b, 70]]) {
        panel.props.active = false; await vue.nextTick(); panel.layout.shown = false
        display.buffer.active.viewportY = 0
        display.buffer.active.baseY += 20
        panel.layout.shown = true; panel.props.active = true
        await panel.flushResize()
        assert.equal(display.buffer.active.viewportY, expected)
        assert.equal(panel.sockets.length, 1)
      }
    }
    assert.ok(a.markers.every(marker => marker.isDisposed))
  } finally { first.unmount(); second.unmount() }
})

test('a tab left at the bottom follows output received while hidden', async () => {
  const panel = mountTerminal()
  try {
    panel.sockets[0].frame({ type: 'ready' }); await panel.flushResize()
    panel.props.active = false; await vue.nextTick()
    const display = panel.displays[0]
    display.buffer.active.baseY = 180; display.buffer.active.viewportY = 0
    panel.props.active = true; await panel.flushResize()
    assert.equal(display.buffer.active.viewportY, 180)
  } finally { panel.unmount() }
})

test('restoring an unchanged buffer position still synchronizes xterm hidden DOM geometry', async () => {
  const panel = mountTerminal()
  try {
    panel.sockets[0].frame({ type: 'ready' }); await panel.flushResize()
    panel.props.active = false; await vue.nextTick()
    const display = panel.displays[0]
    display.buffer.active.baseY = 180; display.buffer.active.viewportY = 180
    display.layoutSynced = false
    panel.props.active = true; await panel.flushResize()
    assert.equal(display.buffer.active.viewportY, 180)
    assert.equal(display.layoutSynced, true)
  } finally { panel.unmount() }
})

test('restoring history follows buffer trimming and keeps disconnected tabs readable', async () => {
  const panel = mountTerminal()
  try {
    panel.sockets[0].frame({ type: 'ready' }); await panel.flushResize()
    const display = panel.displays[0]
    display.buffer.active.viewportY = 40
    panel.props.active = false; await vue.nextTick()
    for (const marker of display.markers) marker.line -= 15
    panel.state.disconnect()
    display.buffer.active.viewportY = 0
    panel.props.active = true; await panel.flushResize()
    assert.equal(display.buffer.active.viewportY, 25)
    assert.equal(panel.sockets.length, 1)
    assert.equal(panel.sockets[0].readyState, 3)
  } finally { panel.unmount() }
})

test('KeepAlive restores history after fitting the revealed terminal', async () => {
  const visible = vue.ref(true), panel = mountTerminal({}, { keepAliveVisible: visible })
  try {
    panel.sockets[0].frame({ type: 'ready' }); await panel.flushResize()
    const display = panel.displays[0]
    display.buffer.active.viewportY = 30
    visible.value = false; await vue.nextTick()
    display.buffer.active.viewportY = 0
    panel.layout.width = 1200
    visible.value = true; await panel.flushResize()
    assert.equal(display.buffer.active.viewportY, 30)
    assert.equal(display.cols, 120)
  } finally { panel.unmount() }
})

test('history evicted from scrollback restores the earliest surviving line', async () => {
  const panel = mountTerminal()
  try {
    panel.sockets[0].frame({ type: 'ready' }); await panel.flushResize()
    const display = panel.displays[0]
    display.buffer.active.viewportY = 10
    panel.props.active = false; await vue.nextTick()
    display.markers.at(-1).dispose()
    display.markers.at(-1).line = -1
    display.buffer.active.viewportY = 100
    panel.props.active = true; await panel.flushResize()
    assert.equal(display.buffer.active.viewportY, 0)
  } finally { panel.unmount() }
})

test('a rapid switch before layout settles retains the original saved history', async () => {
  const panel = mountTerminal()
  try {
    panel.sockets[0].frame({ type: 'ready' }); await panel.flushResize()
    const display = panel.displays[0]
    display.buffer.active.viewportY = 45
    panel.props.active = false; await vue.nextTick()
    display.buffer.active.viewportY = 0
    panel.props.active = true; await vue.nextTick()
    panel.props.active = false; await vue.nextTick()
    await panel.flushResize()
    assert.equal(display.buffer.active.viewportY, 0)
    panel.props.active = true; await panel.flushResize()
    assert.equal(display.buffer.active.viewportY, 45)
    assert.equal(display.markers.length, 1)
  } finally { panel.unmount() }
})

test('manual disconnect closes transport and deadlines while preserving terminal output', async () => {
  const panel = mountTerminal()
  try {
    const socket = panel.sockets[0]
    socket.frame({ type: 'ready', sessionToken: 'old-token', connectionMode: 'tmux' })
    socket.frame({ type: 'output', data: btoa('running task\r\n') })
    const lateFrame = socket.onmessage
    const sentBeforeDisconnect = [...socket.sent]
    panel.state.disconnect()
    assert.equal(socket.readyState, 3)
    assert.equal(panel.state.disconnected.value, true)
    assert.equal(panel.displays[0].output, 'running task\r\n')
    assert.equal(panel.displays[0].disposed, false)
    assert.equal(panel.timers.size, 0)
    assert.deepEqual(socket.sent, sentBeforeDisconnect)
    assert.deepEqual(panel.events.filter(([type]) => type === 'disconnected'), [['disconnected', { manual: true }]])
    lateFrame({ data: JSON.stringify({ type: 'ready', sessionToken: 'stale-token' }) })
    lateFrame({ data: JSON.stringify({ type: 'output', data: btoa('stale output') }) })
    await vue.nextTick()
    assert.equal(panel.events.filter(([type]) => type === 'ready').length, 1)
    assert.equal(panel.events.some(([type]) => type === 'error'), false)
    assert.equal(panel.displays[0].output, 'running task\r\n')
    assert.equal(panel.sockets.length, 1)
  } finally { panel.unmount() }
})

test('manual reconnect reuses the same terminal identity and ignores old connection failures', () => {
  const panel = mountTerminal()
  try {
    const oldSocket = panel.sockets[0], lateClose = oldSocket.onclose
    panel.state.disconnect()
    panel.state.reconnect()
    lateClose()
    panel.sockets[1].frame({ type: 'ready', sessionToken: 'new-token', connectionMode: 'tmux' })
    assert.deepEqual(panel.urls.map(({ id, terminalId, transport }) => ({ id, terminalId, transport })), [
      { id: 1, terminalId: 'persistent-terminal', transport: '' },
      { id: 1, terminalId: 'persistent-terminal', transport: '' }
    ])
    assert.equal(panel.state.disconnected.value, false)
    assert.equal(panel.events.some(([type]) => type === 'error'), false)
    assert.equal(panel.events.at(-1)[1].sessionToken, 'new-token')
  } finally { panel.unmount() }
})

test('toolbar reconnect publishes connecting state and SSH retry preserves the terminal identity', () => {
  const panel = mountTerminal({ paused: true })
  try {
    panel.state.reconnectPlainSsh()
    assert.deepEqual(panel.events.at(-1), ['connecting'])
    assert.equal(panel.state.disconnected.value, false)
    assert.equal(panel.urls[0].terminalId, 'persistent-terminal')
    assert.equal(panel.urls[0].transport, 'ssh')
    panel.sockets[0].frame({ type: 'ready', sessionToken: 'ssh-token', connectionMode: 'ssh' })
    panel.sockets[0].onerror()
    assert.equal(panel.events.at(-2)[0], 'disconnected')
    panel.state.reconnect()
    assert.deepEqual(panel.events.at(-1), ['connecting'])
    assert.equal(panel.urls[1].transport, 'ssh')
  } finally { panel.unmount() }
})

test('a manually paused tab does not reconnect when another view mounts or its encoding changes', async () => {
  const panel = mountTerminal({ paused: true })
  try {
    assert.equal(panel.sockets.length, 0)
    assert.equal(panel.state.disconnected.value, true)
    panel.props.encoding = 'GBK'
    await vue.nextTick()
    assert.equal(panel.sockets.length, 0)
    panel.props.paused = false
    await vue.nextTick()
    assert.equal(panel.sockets.length, 0)
    panel.state.reconnect()
    assert.equal(panel.sockets.length, 1)
    assert.equal(panel.urls[0].terminalId, 'persistent-terminal')
  } finally { panel.unmount() }
})

test('pausing an existing secondary view closes its transport without a connection error', async () => {
  const panel = mountTerminal({ paused: false })
  try {
    panel.props.paused = true
    await vue.nextTick()
    assert.equal(panel.sockets[0].readyState, 3)
    assert.equal(panel.state.disconnected.value, true)
    assert.equal(panel.events.some(([type]) => type === 'error'), false)
  } finally { panel.unmount() }
})

const resizeFrames = socket => socket.sent.filter(frame => frame.type === 'resize')

test('background reconnect retains the last measured size then resynchronizes the visible container', async () => {
  const panel = mountTerminal()
  try {
    panel.sockets[0].frame({ type: 'ready', sessionToken: 'first' })
    await panel.flushResize()
    assert.deepEqual(resizeFrames(panel.sockets[0]).at(-1), { type: 'resize', cols: 100, rows: 30 })
    panel.document.visibilityState = 'hidden'; panel.layout.shown = false
    const fits = panel.fits
    panel.state.reconnect()
    assert.equal(panel.urls[1].cols, 100)
    assert.equal(panel.urls[1].rows, 30)
    panel.sockets[1].frame({ type: 'ready', sessionToken: 'second' })
    await panel.flushResize()
    assert.equal(panel.fits, fits)
    assert.deepEqual(resizeFrames(panel.sockets[1]), [])
    assert.equal(panel.displays[1].focuses, 0)
    panel.layout.shown = true; panel.layout.width = 1440; panel.layout.height = 900
    panel.document.visibilityState = 'visible'
    panel.document.dispatchEvent(new Event('visibilitychange'))
    await panel.flushResize()
    assert.deepEqual(resizeFrames(panel.sockets[1]), [{ type: 'resize', cols: 144, rows: 45 }])
    assert.equal(panel.sockets.length, 2)
  } finally { panel.unmount() }
})

test('resize bursts use the latest layout once; ordinary unchanged observations do not resend', async () => {
  const panel = mountTerminal()
  try {
    const socket = panel.sockets[0]
    socket.frame({ type: 'ready', sessionToken: 'first' }); await panel.flushResize()
    const before = resizeFrames(socket).length, fits = panel.fits
    for (const width of [1100, 1200, 1300]) { panel.layout.width = width; panel.observers[0].callback([]) }
    assert.equal(panel.fits, fits)
    await panel.flushResize()
    assert.equal(panel.fits, fits + 1)
    assert.deepEqual(resizeFrames(socket).at(-1), { type: 'resize', cols: 130, rows: 30 })
    assert.equal(resizeFrames(socket).length, before + 1)
    panel.observers[0].callback([]); await panel.flushResize()
    assert.equal(resizeFrames(socket).length, before + 1)
    panel.window.dispatchEvent(new Event('focus')); await panel.flushResize()
    assert.equal(resizeFrames(socket).length, before + 2)
    panel.layout.width = 1400
    panel.window.visualViewport.dispatchEvent(new Event('resize')); await panel.flushResize()
    assert.deepEqual(resizeFrames(socket).at(-1), { type: 'resize', cols: 140, rows: 30 })
  } finally { panel.unmount() }
})

test('inactive terminal does not fit or steal focus and resizes after its tab becomes active', async () => {
  const panel = mountTerminal({ active: false })
  try {
    const socket = panel.sockets[0]
    socket.frame({ type: 'ready', sessionToken: 'background' }); await panel.flushResize()
    assert.deepEqual(resizeFrames(socket), [])
    assert.equal(panel.displays[0].focuses, 0)
    assert.equal(panel.fits, 0)
    assert.equal(panel.urls[0].cols, 80)
    assert.equal(panel.urls[0].rows, 24)
    panel.layout.width = 960; panel.layout.height = 480
    panel.props.active = true; await panel.flushResize()
    assert.deepEqual(resizeFrames(socket), [{ type: 'resize', cols: 96, rows: 24 }])
    assert.equal(panel.sockets.length, 1)
  } finally { panel.unmount() }
})

test('font changes notify remote dimensions and zero-sized containers never send a collapsed resize', async () => {
  const panel = mountTerminal()
  try {
    const socket = panel.sockets[0]
    socket.frame({ type: 'ready', sessionToken: 'font' }); await panel.flushResize()
    panel.props.fontSize = 20; await panel.flushResize()
    assert.deepEqual(resizeFrames(socket).at(-1), { type: 'resize', cols: 70, rows: 21 })
    const frames = resizeFrames(socket).length, fits = panel.fits
    panel.layout.width = 0; panel.layout.height = 0
    panel.observers[0].callback([]); await panel.flushResize()
    assert.equal(panel.fits, fits)
    assert.equal(resizeFrames(socket).length, frames)
  } finally { panel.unmount() }
})

test('disconnect and unmount cancel pending layout work and visibility listeners', async () => {
  const panel = mountTerminal(), socket = panel.sockets[0]
  socket.frame({ type: 'ready', sessionToken: 'closing' })
  await vue.nextTick()
  panel.state.disconnect()
  const fits = panel.fits
  await panel.flushResize()
  panel.document.dispatchEvent(new Event('visibilitychange')); panel.window.dispatchEvent(new Event('focus'))
  await panel.flushResize()
  assert.equal(panel.fits, fits)
  assert.deepEqual(resizeFrames(socket), [])
  panel.unmount()
  panel.window.dispatchEvent(new Event('resize')); panel.document.dispatchEvent(new Event('visibilitychange'))
  await panel.flushResize()
  assert.equal(panel.frames.size, 0)
  assert.ok(panel.observers.every(observer => observer.disconnected))
  for (const target of [panel.document, panel.window, panel.document.fonts, panel.window.visualViewport]) {
    assert.equal(target.listeners.size, 0)
  }
})

test('restoring the page resends dimensions even when the measured grid has not changed', async () => {
  const panel = mountTerminal()
  try {
    const socket = panel.sockets[0]
    socket.frame({ type: 'ready', sessionToken: 'same-grid' }); await panel.flushResize()
    panel.document.visibilityState = 'hidden'
    panel.document.dispatchEvent(new Event('visibilitychange')); await panel.flushResize()
    assert.equal(resizeFrames(socket).length, 1)
    panel.document.visibilityState = 'visible'
    panel.document.dispatchEvent(new Event('visibilitychange'))
    panel.window.dispatchEvent(new Event('pageshow'))
    await panel.flushResize()
    assert.deepEqual(resizeFrames(socket), [
      { type: 'resize', cols: 100, rows: 30 }, { type: 'resize', cols: 100, rows: 30 }
    ])
  } finally { panel.unmount() }
})

test('unmeasured xterm fonts retry for a bounded number of frames without sending stale dimensions', async () => {
  const panel = mountTerminal()
  try {
    const socket = panel.sockets[0]
    panel.layout.measured = false
    socket.frame({ type: 'ready', sessionToken: 'metrics' })
    for (let i = 0; i < 4; i++) await panel.flushResize()
    assert.deepEqual(resizeFrames(socket), [])
    assert.equal(panel.frames.size, 0)
    panel.layout.measured = true; panel.layout.width = 1200
    panel.document.fonts.dispatchEvent(new Event('loadingdone')); await panel.flushResize()
    assert.deepEqual(resizeFrames(socket), [{ type: 'resize', cols: 120, rows: 30 }])
  } finally { panel.unmount() }
})

test('KeepAlive stops pending layout work and background ready focus, then resynchronizes on activation', async () => {
  const visible = vue.ref(true), panel = mountTerminal({}, { keepAliveVisible: visible })
  try {
    const socket = panel.sockets[0], fits = panel.fits
    visible.value = false; await vue.nextTick()
    socket.frame({ type: 'ready', sessionToken: 'deactivated' }); await panel.flushResize()
    assert.equal(panel.fits, fits)
    assert.equal(panel.displays[0].focuses, 0)
    assert.deepEqual(resizeFrames(socket), [])
    panel.layout.width = 1200; panel.layout.height = 800
    visible.value = true; await panel.flushResize()
    assert.deepEqual(resizeFrames(socket), [{ type: 'resize', cols: 120, rows: 40 }])
    panel.window.dispatchEvent(new Event('focus')); await vue.nextTick()
    assert.equal(panel.frames.size, 1)
    visible.value = false; await vue.nextTick()
    assert.equal(panel.frames.size, 0)
    await panel.flushResize()
    assert.equal(resizeFrames(socket).length, 1)
    assert.equal(panel.frames.size, 0)
    visible.value = true; await panel.flushResize()
    assert.equal(resizeFrames(socket).length, 2)
    assert.equal(panel.sockets.length, 1)
  } finally { panel.unmount() }
})
