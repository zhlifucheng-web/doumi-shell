import test from 'node:test'
import assert from 'node:assert/strict'
import * as vue from 'vue'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'
import { createTerminalConnectionGuard } from '../src/utils/terminalConnectionGuard.js'
import { createHistoryRequest } from '../src/utils/terminalHistory.js'
import { tmuxCompatibility } from '../src/utils/tmuxCompatibility.js'

const filename = new URL('../src/components/ssh/SshTerminal.vue', import.meta.url)

function mountTerminal(props = {}) {
  const sockets = [], displays = [], events = [], urls = [], timers = new Set()
  class Socket {
    static CONNECTING = 0
    static OPEN = 1
    constructor(url) { this.url = url; this.readyState = 1; this.sent = []; sockets.push(this) }
    send(data) { this.sent.push(JSON.parse(data)) }
    close() { this.readyState = 3; this.onclose?.() }
    frame(data) { this.onmessage?.({ data: JSON.stringify(data) }) }
  }
  class Display {
    constructor() { this.cols = 80; this.rows = 24; this.options = {}; this.output = ''; this.disposed = false; displays.push(this) }
    attachCustomKeyEventHandler() {}
    loadAddon() {}
    open() {}
    onData() { return { dispose() {} } }
    onBinary() { return { dispose() {} } }
    focus() {}
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
    '@xterm/addon-fit': { FitAddon: class { fit() {} } },
    '@xterm/addon-web-links': { WebLinksAddon: class {} },
    '@xterm/addon-search': { SearchAddon: class {} },
    '@/api/sshConsole': { buildTerminalWsUrl: (id, options) => { urls.push({ id, ...options }); return 'ws://test/terminal' } },
    '@/utils/terminalColorEnhancer': { enhanceTerminalOutput: text => text },
    '@/composables/useBackground': { useBackground: () => ({ background: vue.ref({ enabled: false }) }) },
    '@/composables/useTheme': { useTheme: () => ({ effectiveDark: vue.ref(true) }) },
    '@/composables/useTerminalTheme': { useTerminalTheme: () => ({ activeTheme: vue.ref(null) }) }
  }
  const mounted = mountSfcSetup(filename, {
    modules,
    props: { accountId: 1, terminalId: 'persistent-terminal', encoding: 'UTF-8', ...props,
      onDisconnected: payload => events.push(['disconnected', payload]),
      onConnectionError: payload => events.push(['error', payload]),
      onReady: payload => events.push(['ready', payload]) },
    globals: { WebSocket: Socket, ResizeObserver: class { observe() {} disconnect() {} } },
    onSetup: state => { state.termContainer.value = { querySelector: () => ({}) } }
  })
  return { ...mounted, sockets, displays, events, urls, timers }
}

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
