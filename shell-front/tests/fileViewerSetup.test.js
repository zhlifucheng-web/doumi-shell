import test from 'node:test'
import assert from 'node:assert/strict'
import * as vue from 'vue'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'
import * as text from '../src/utils/fileViewerText.js'
import * as helpers from '../src/utils/fileTypeHelper.js'
import { createViewerTail } from '../src/composables/useViewerTail.js'
import { useViewerFilter } from '../src/composables/useViewerFilter.js'

function mountViewer({ read, standalone = true, encoding = 'UTF-8' } = {}) {
  const listeners = {}, timers = new Map(), copied = [], messages = [], sockets = []
  let selected = ''; let sequence = 0
  const body = { scrollTop: 100, scrollHeight: 200, clientHeight: 100, querySelector: () => null, contains: () => true }
  const doc = {
    addEventListener: (name, handler) => { listeners[name] = handler },
    removeEventListener: name => { delete listeners[name] },
    getSelection: () => ({ toString: () => selected, rangeCount: selected ? 1 : 0, getRangeAt: () => ({ commonAncestorContainer: {} }) }),
    activeElement: null
  }
  const modules = {
    'element-plus': { ElMessage: { error: m => messages.push(m), success: () => {}, warning: () => {} } },
    '@/api/sshConsole': {
      sftpReadChunk: read || (async () => ({ offset: 0, length: 6, totalSize: 6, hasMoreBefore: false, hasMoreAfter: false, contentBase64: btoa('hello\n') })),
      buildFileTailWsUrl: () => 'ws://localhost/ws/ssh-console/file-tail',
      sftpFilterStart: async () => ({ scanId: 'a' }), sftpFilterNext: async () => ({ rows: [], offset: 6, totalSize: 6, eof: true }), sftpFilterCancel: async () => {}
    },
    '@/utils/fileViewerText': text, '@/utils/fileTypeHelper': helpers,
    '@/composables/useViewerFilter': { useViewerFilter },
    '@/composables/useViewerTail': { createViewerTail: options => createViewerTail({ ...options, connect: () => { const socket = { readyState: 1, sent: [], send(v) { this.sent.push(JSON.parse(v)) }, close() {}, frame(v) { this.onmessage({ data: JSON.stringify(v) }) } }; sockets.push(socket); queueMicrotask(() => { socket.onopen(); socket.frame({ type: 'ready', tailId: 't' }) }); return socket } }) },
    '@/composables/useTheme': { useTheme: () => ({ effectiveDark: vue.ref(false), setPreference: () => {} }) }
  }
  const mounted = mountSfcSetup(new URL('../src/components/ssh/SshFileViewer.vue', import.meta.url), {
    modules, props: { sessionToken: 'token', path: '/app.log', name: 'app.log', standalone, encoding },
    globals: { document: doc, window: { getSelection: doc.getSelection }, navigator: { clipboard: { writeText: async s => copied.push(s) } },
      setInterval: fn => { const id = ++sequence; timers.set(id, fn); return id }, clearInterval: id => timers.delete(id) },
    onSetup: state => { state.viewerBodyRef.value = body; state.rootRef.value = { contains: () => true } }
  })
  return { ...mounted, sockets, listeners, timers, body, copied, messages, select: s => { selected = s; listeners.selectionchange?.() } }
}
const flush = async () => { await new Promise(setImmediate); await vue.nextTick() }

test('initial file bottom enables Tail and upward reading pauses it', async () => {
  const viewer = mountViewer()
  try {
    await flush()
    assert.equal(viewer.state.autoFollow.value, true)
    assert.equal(viewer.sockets.length, 1)
    assert.equal(viewer.sockets[0].sent[0].type, 'start')
    viewer.body.scrollTop = 0; viewer.body.scrollHeight = 500
    viewer.state.onViewerScroll()
    assert.equal(viewer.state.autoFollow.value, false)
    viewer.body.scrollTop = 400
    viewer.state.onViewerScroll()
    assert.equal(viewer.state.autoFollow.value, true)
    viewer.state.hasMoreAfter.value = true
    viewer.state.setFollowing(false)
    viewer.state.onViewerScroll()
    assert.equal(viewer.state.autoFollow.value, false)
  } finally { viewer.unmount() }
  assert.equal(viewer.timers.size, 0)
  assert.deepEqual(viewer.listeners, {})
})

test('selecting text pauses Tail and copies the selected text', async () => {
  const viewer = mountViewer()
  try {
    await flush(); viewer.select('ell')
    assert.equal(viewer.state.autoFollow.value, false)
    await viewer.state.copySelection()
    assert.deepEqual(viewer.copied, ['ell'])
    await viewer.state.copyDisplayed()
    assert.deepEqual(viewer.copied, ['ell', 'hello\n'])
  } finally { viewer.unmount() }
})

test('viewer Ctrl+F captures default find and focuses its own search', async () => {
  const viewer = mountViewer(); const calls = []
  try {
    await flush()
    assert.equal(viewer.state.toolsVisible.value, false)
    viewer.state.searchInputRef.value = { focus: () => calls.push('focus') }
    viewer.listeners.keydown({ key: 'f', ctrlKey: true, preventDefault: () => calls.push('prevent'), stopPropagation: () => calls.push('stop') })
    await flush()
    assert.equal(viewer.state.toolsVisible.value, true)
    assert.deepEqual(calls, ['prevent', 'stop', 'focus'])
  } finally { viewer.unmount() }
})

test('search tools toggle and Escape retain the current search and active filter', async () => {
  const viewer = mountViewer()
  try {
    await flush()
    viewer.state.searchKeyword.value = 'hello'; viewer.state.filterKeyword.value = 'error'
    await viewer.state.startFilter()
    await viewer.state.toggleSearchTools()
    assert.equal(viewer.state.toolsVisible.value, true)
    viewer.state.closeSearchTools()
    assert.equal(viewer.state.toolsVisible.value, false)
    assert.equal(viewer.state.searchKeyword.value, 'hello')
    assert.equal(viewer.state.filterKeyword.value, 'error')
    assert.equal(viewer.state.filter.active.value, true)
    await viewer.state.toggleSearchTools()
    await viewer.state.toggleSearchTools()
    assert.equal(viewer.state.toolsVisible.value, false)
  } finally { viewer.unmount() }
})

test('a pushed batch arriving after selection waits for resume without repainting or ack', async () => {
  const viewer = mountViewer()
  try {
    await flush(); viewer.select('hello')
    viewer.sockets[0].frame({ type: 'data', sequence: 1, offset: 10, totalSize: 10, content: 'new\n' })
    await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\n')
    assert.equal(viewer.sockets[0].sent.at(-1).type, 'pause')
    viewer.select(''); viewer.state.setFollowing(true); await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\nnew\n')
    assert.equal(viewer.sockets[0].sent.at(-1).type, 'ack')
  } finally { viewer.unmount() }
})

test('filtering from Tail resumes incremental following only after full historical scan', async () => {
  const viewer = mountViewer()
  try {
    await flush()
    viewer.state.filterKeyword.value = 'hello'
    await viewer.state.startFilter()
    assert.equal(viewer.state.autoFollow.value, true)
    assert.equal(viewer.state.filter.eof.value, true)
    viewer.state.filter.eof.value = false
    assert.equal(viewer.state.canFilterTail.value, true, 'already scanned EOF remains tail-ready while catching up')
  } finally { viewer.unmount() }
})

test('initial UTF-8 tail does not flush a partial character before its pushed continuation', async () => {
  const viewer = mountViewer({ read: async () => ({ offset: 0, length: 3, totalSize: 3, contentBase64: btoa(String.fromCharCode(0x61, 0xe4, 0xb8)) }) })
  try {
    await flush()
    assert.equal(viewer.state.combinedText.value, 'a')
    viewer.sockets[0].frame({ type: 'data', sequence: 1, offset: 4, totalSize: 4, content: '中' })
    await flush()
    assert.equal(viewer.state.combinedText.value, 'a中')
  } finally { viewer.unmount() }
})

test('invalid byte bounds or mismatched scan IDs stop Tail without corrupting content', async () => {
  const viewer = mountViewer()
  try {
    await flush()
    viewer.sockets[0].frame({ type: 'data', sequence: 1, offset: 'bad', totalSize: 10, content: 'bad' }); await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\n')
    assert.equal(viewer.state.autoFollow.value, false)
    assert.equal(viewer.messages.length, 1)
  } finally { viewer.unmount() }
  const filtered = mountViewer()
  try {
    await flush(); filtered.state.filterKeyword.value = 'x'; await filtered.state.startFilter(); await flush()
    filtered.sockets.at(-1).frame({ type: 'data', sequence: 1, scanId: 'wrong', offset: 7, totalSize: 7, rows: [] }); await flush()
    assert.equal(filtered.state.autoFollow.value, false)
    assert.equal(filtered.messages.length, 1)
  } finally { filtered.unmount() }
})

test('a failed filtered Tail requires a fresh scan before following again', async () => {
  const viewer = mountViewer()
  try {
    await flush(); viewer.state.filterKeyword.value = 'x'; await viewer.state.startFilter(); await flush()
    viewer.sockets.at(-1).frame({ type: 'error', message: 'Tail 已失效，请重新筛选' }); await flush()
    assert.equal(viewer.state.canFilterTail.value, false)
    assert.match(viewer.state.filter.error.value, /请重新筛选/)
  } finally { viewer.unmount() }
})

test('Tail appends stable render blocks and selection holds late data until explicit resume', async () => {
  const viewer = mountViewer()
  try {
    await flush()
    const socket = viewer.sockets[0], first = viewer.state.renderedBlocks.value[0]
    socket.frame({ type: 'data', sequence: 1, offset: 10, totalSize: 10, content: 'new\n' }); await flush()
    assert.equal(viewer.state.renderedBlocks.value[0], first)
    assert.equal(viewer.state.combinedText.value, 'hello\nnew\n')
    viewer.select('hello')
    const frozen = viewer.state.renderedBlocks.value, top = viewer.body.scrollTop
    socket.frame({ type: 'data', sequence: 2, offset: 15, totalSize: 15, content: 'held\n' }); await flush()
    assert.equal(viewer.state.renderedBlocks.value, frozen)
    assert.equal(viewer.body.scrollTop, top)
    assert.equal(socket.sent.at(-1).type, 'pause')
    viewer.select('');viewer.state.setFollowing(true);await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\nnew\nheld\n')
    assert.deepEqual(socket.sent.at(-1), { type: 'ack', sequence: 2 })
    socket.frame({ type: 'data', sequence: 2, offset: 15, totalSize: 15, content: 'held\n' });await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\nnew\nheld\n')
  } finally { viewer.unmount() }
})
