import test from 'node:test'
import assert from 'node:assert/strict'
import * as vue from 'vue'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'
import * as text from '../src/utils/fileViewerText.js'
import * as helpers from '../src/utils/fileTypeHelper.js'
import { createViewerTail } from '../src/composables/useViewerTail.js'
import { createViewerSession, isViewerSessionError } from '../src/composables/useViewerSession.js'
import { useViewerFilter } from '../src/composables/useViewerFilter.js'
import { useViewerLocalFilter } from '../src/composables/useViewerLocalFilter.js'

function mountViewer({ read, api = {}, standalone = true, encoding = 'UTF-8', automaticHistory = true } = {}) {
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
      sftpFilterStart: async () => ({ scanId: 'a', mode: 'remote' }), sftpFilterNext: () => assert.fail('HTTP next must not be used'), sftpFilterCancel: async () => {},
      getSessionContext: async () => ({ accountId: 57 }), openViewerSession: async () => ({ sessionToken: 'renewed' }), closeViewerSession: async () => {}, ...api
    },
    '@/utils/fileViewerText': text, '@/utils/fileTypeHelper': helpers,
    '@/composables/useViewerFilter': { useViewerFilter },
    '@/composables/useViewerLocalFilter': { useViewerLocalFilter },
    '@/composables/useViewerSession': { createViewerSession, isViewerSessionError },
    '@/composables/useViewerTail': { createViewerTail: options => createViewerTail({ ...options, connect: () => {
      let source, terminal = false, pageSequence = 0
      const socket = { readyState: 1, sent: [], send(v) {
        const frame = JSON.parse(v); this.sent.push(frame)
        if (frame.type === 'start') {
          source = frame
          if (source.historical && automaticHistory) queueMicrotask(async () => {
            const page = await (api.historyPage?.(source) || { rows: [], offset: 6, totalSize: 6, eof: true })
            terminal = page.eof || page.limited
            socket.frame({ type: 'data', scanId: source.scanId, sequence: ++pageSequence, ...page })
          })
        } else if (source?.historical && frame.type === 'ack' && terminal) queueMicrotask(() => socket.frame({ type: 'complete' }))
      }, close() { this.readyState = 3 }, frame(v) { this.onmessage({ data: JSON.stringify(v) }) } }
      sockets.push(socket); queueMicrotask(() => { socket.onopen(); socket.frame({ type: 'ready', tailId: 't' }) }); return socket
    } }) },
    '@/composables/useTheme': { useTheme: () => ({ effectiveDark: vue.ref(false), setPreference: () => {} }) }
  }
  const mounted = mountSfcSetup(new URL('../src/components/ssh/SshFileViewer.vue', import.meta.url), {
    modules, props: { sessionToken: 'token', path: '/app.log', name: 'app.log', standalone, encoding },
    globals: { document: doc, window: { getSelection: doc.getSelection }, navigator: { clipboard: { writeText: async s => copied.push(s) } },
      setInterval: fn => { const id = ++sequence; timers.set(id, fn); return id }, clearInterval: id => timers.delete(id),
      setTimeout: fn => { const id = ++sequence; timers.set(id, fn); return id }, clearTimeout: id => timers.delete(id) },
    onSetup: state => { state.viewerBodyRef.value = body; state.rootRef.value = { contains: () => true } }
  })
  return { ...mounted, sockets, listeners, timers, body, copied, messages, select: s => { selected = s; listeners.selectionchange?.() } }
}
const flush = async () => { await new Promise(setImmediate); await vue.nextTick() }

async function startWholeFilter(viewer, keyword) { viewer.state.searchMode.value = 'filter'; await vue.nextTick(); return viewer.state.startFilter(keyword) }

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
    viewer.state.searchInputRef.value = { focus: () => calls.push('focus'), select: () => calls.push('select') }
    viewer.listeners.keydown({ key: 'f', ctrlKey: true, preventDefault: () => calls.push('prevent'), stopPropagation: () => calls.push('stop') })
    await flush()
    assert.equal(viewer.state.toolsVisible.value, true)
    assert.deepEqual(calls, ['prevent', 'stop', 'focus', 'select'])
  } finally { viewer.unmount() }
})

test('search tools toggle and Escape retain the current search and active filter', async () => {
  const viewer = mountViewer()
  try {
    await flush()
    viewer.state.searchKeyword.value = 'error'
    await startWholeFilter(viewer)
    await viewer.state.toggleSearchTools()
    assert.equal(viewer.state.toolsVisible.value, true)
    viewer.state.closeSearchTools()
    assert.equal(viewer.state.toolsVisible.value, false)
    assert.equal(viewer.state.searchKeyword.value, 'error')
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
    viewer.state.searchKeyword.value = 'hello'
    await startWholeFilter(viewer)
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
    await flush(); filtered.state.searchKeyword.value = 'x'; await startWholeFilter(filtered); await flush()
    filtered.sockets.at(-1).frame({ type: 'data', sequence: 1, scanId: 'wrong', offset: 7, totalSize: 7, rows: [] }); await flush()
    assert.equal(filtered.state.autoFollow.value, false)
    assert.equal(filtered.messages.length, 1)
  } finally { filtered.unmount() }
})

test('a failed filtered Tail requires a fresh scan before following again', async () => {
  const viewer = mountViewer()
  try {
    await flush(); viewer.state.searchKeyword.value = 'x'; await startWholeFilter(viewer); await flush()
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

test('first Tail append to an empty file creates one unique keyed content block', async () => {
  const viewer = mountViewer({ read: async () => ({ offset: 0, length: 0, totalSize: 0, contentBase64: '' }) })
  try {
    await flush()
    viewer.sockets[0].frame({ type: 'data', sequence: 1, offset: 5, totalSize: 5, content: 'first' });await flush()
    const blocks = viewer.state.renderedBlocks.value
    assert.equal(blocks.length, 1)
    assert.equal(new Set(blocks.map(b => b.key)).size, blocks.length)
    assert.equal(viewer.state.combinedText.value, 'first')
  } finally { viewer.unmount() }
})


test('an expired initial session reconnects once and reads using the renewed token without error toasts', async () => {
  const tokens = []
  const viewer = mountViewer({ read: async token => {
    tokens.push(token)
    if (token === 'token') throw new Error('入参不能为空: 会话不存在或已过期')
    return { offset: 0, length: 4, totalSize: 4, contentBase64: btoa('new\n') }
  } })
  try {
    await flush(); await flush()
    assert.equal(viewer.state.combinedText.value, 'new\n')
    assert.deepEqual(tokens, ['token', 'renewed'])
    assert.equal(viewer.sockets.at(-1).sent[0].sessionToken, 'renewed')
    assert.deepEqual(viewer.messages, [])
  } finally { viewer.unmount() }
})

test('a reset file reloads from the beginning and continues Tail with fresh sequence state', async () => {
  let value = 'hello\n'; const reads = []
  const viewer = mountViewer({ read: async (token, path, options) => {
    reads.push(options)
    return { offset: 0, length: value.length, totalSize: value.length, contentBase64: btoa(value) }
  } })
  try {
    await flush(); value = 'reset\n'
    viewer.sockets[0].frame({ type: 'error', code: 'FILE_CHANGED', message: '文件已截断' })
    await flush(); await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\n===================truncated===================\nreset\n')
    assert.equal(reads.at(-1).fromEnd, false)
    assert.equal(reads.at(-1).offset, 0)
    assert.equal(viewer.state.autoFollow.value, true)
    viewer.sockets.at(-1).frame({ type: 'data', sequence: 1, offset: 11, totalSize: 11, content: 'next\n' })
    await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\n===================truncated===================\nreset\nnext\n')
    assert.deepEqual(viewer.messages, [])
  } finally { viewer.unmount() }
})

test('file reset while selected preserves text and scroll until explicit following resumes', async () => {
  let value = 'hello\n', reads = 0
  const viewer = mountViewer({ read: async () => {
    reads++; return { offset: 0, length: value.length, totalSize: value.length, contentBase64: btoa(value) }
  } })
  try {
    await flush(); viewer.select('hello'); const top = viewer.body.scrollTop
    value = 'reset\n'; viewer.sockets[0].frame({ type: 'error', code: 'FILE_CHANGED', message: '文件已截断' })
    await flush()
    assert.equal(reads, 1)
    assert.equal(viewer.state.combinedText.value, 'hello\n')
    assert.equal(viewer.body.scrollTop, top)
    viewer.select(''); viewer.state.setFollowing(true); await flush(); await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\n===================truncated===================\nreset\n')
    assert.equal(viewer.state.autoFollow.value, true)
  } finally { viewer.unmount() }
})

test('filtered Tail reset rescans the same keyword and resumes a new filter subscription', async () => {
  let scan = 0; const keywords = []
  const viewer = mountViewer({ api: {
    sftpFilterStart: async data => { keywords.push(data.keyword); return { scanId: `scan-${++scan}` } },
    historyPage: async ({scanId}) => ({ scanId, rows: [{ lineNumber: 1, text: scanId, matched: true }], offset: 6, totalSize: 6, eof: true })
  } })
  try {
    await flush(); viewer.state.searchKeyword.value = 'hit'; await startWholeFilter(viewer); await flush()
    viewer.sockets.at(-1).frame({ type: 'error', code: 'FILE_CHANGED', message: '文件已改变或截断' })
    await flush(); await flush()
    assert.deepEqual(keywords, ['hit', 'hit'])
    assert.equal(viewer.state.displayedText.value, 'scan-1\n===================truncated===================\nscan-2')
    assert.equal(viewer.state.autoFollow.value, true)
    assert.equal(viewer.sockets.at(-1).sent[0].scanId, 'scan-2')
    assert.deepEqual(viewer.messages, [])
  } finally { viewer.unmount() }
})


test('pausing an in-flight reset reload rejects its late result and resumes without duplicates', async () => {
  let resolve, reads = 0
  const viewer = mountViewer({ read: async () => {
    if (++reads === 2) return new Promise(done => { resolve = done })
    return { offset: 0, length: 6, totalSize: 6, contentBase64: btoa(reads === 1 ? 'hello\n' : 'fresh\n') }
  } })
  try {
    await flush(); viewer.sockets[0].frame({ type: 'error', code: 'FILE_CHANGED' }); await flush()
    viewer.select('hello'); const top = viewer.body.scrollTop
    resolve({ offset: 0, length: 5, totalSize: 5, contentBase64: btoa('late\n') }); await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\n'); assert.equal(viewer.body.scrollTop, top)
    viewer.select(''); viewer.state.setFollowing(true); await flush(); await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\n===================truncated===================\nfresh\n')
    assert.equal(viewer.state.autoFollow.value, true)
  } finally { viewer.unmount() }
})

test('closing while a recovered session is opening releases it without reading or creating a new tail', async () => {
  let resolve; const released = []; let reads = 0
  const viewer = mountViewer({ read: async () => { reads++; return { offset: 0, length: 6, totalSize: 6, contentBase64: btoa('hello\n') } },
    api: { openViewerSession: () => new Promise(done => { resolve = done }), closeViewerSession: async token => released.push(token) } })
  await flush(); viewer.sockets[0].frame({ type: 'error', code: 'SESSION_EXPIRED' }); await flush()
  viewer.unmount(); resolve({ sessionToken: 'late-token' }); await flush()
  assert.deepEqual(released, ['late-token']); assert.equal(reads, 1); assert.equal(viewer.sockets.length, 1)
})

test('expired Tail state rebuilds content and resumes without retaining the obsolete tail ID', async () => {
  const viewer = mountViewer()
  try {
    await flush(); viewer.sockets[0].frame({ type: 'error', code: 'TAIL_EXPIRED' }); await flush(); await flush()
    assert.equal(viewer.sockets.length, 2)
    assert.equal(viewer.sockets[1].sent[0].type, 'start')
    assert.equal(viewer.state.combinedText.value, 'hello\n')
    assert.equal(viewer.state.autoFollow.value, true)
  } finally { viewer.unmount() }
})


test('client-side truncation bounds trigger the same reload path as server file-change events', async () => {
  let content = 'hello\n'
  const viewer = mountViewer({ read: async () => ({ offset: 0, length: content.length, totalSize: content.length, contentBase64: btoa(content) }) })
  try {
    await flush(); content = 'new\n'
    viewer.sockets[0].frame({ type: 'data', sequence: 1, offset: 4, totalSize: 4, content: 'new\n' })
    await flush(); await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\n===================truncated===================\nnew\n')
    assert.equal(viewer.state.autoFollow.value, true)
    assert.deepEqual(viewer.messages, [])
  } finally { viewer.unmount() }
})


test('transient recovery failure schedules one retry and pause cancels it until explicit resume', async () => {
  let reads = 0
  const viewer = mountViewer({ read: async () => {
    if (++reads === 2) throw new Error('No such file')
    return { offset: 0, length: 6, totalSize: 6, contentBase64: btoa(reads === 1 ? 'hello\n' : 'fresh\n') }
  } })
  try {
    await flush(); viewer.sockets[0].frame({ type: 'error', code: 'FILE_CHANGED' }); await flush()
    assert.equal(viewer.timers.size, 1)
    assert.equal(viewer.state.combinedText.value, 'hello\n')
    viewer.state.setFollowing(false); assert.equal(viewer.timers.size, 0)
    viewer.state.setFollowing(true); await flush(); await flush()
    assert.equal(viewer.state.combinedText.value, 'hello\n===================truncated===================\nfresh\n')
    assert.equal(viewer.state.autoFollow.value, true)
    assert.deepEqual(viewer.messages, [])
  } finally { viewer.unmount() }
  assert.equal(viewer.timers.size, 0)
})


test('new normal Tail carries the HTTP byte boundary so startup cannot silently skip a replaced file', async () => {
  const viewer = mountViewer()
  try {
    await flush()
    assert.equal(viewer.sockets[0].sent[0].anchorBase64, 'aGVsbG8K')
  } finally { viewer.unmount() }
})


test('repeated resets keep bounded history and unique render keys while new offsets restart at zero', async () => {
  let value = 'old-without-newline'
  const viewer = mountViewer({ read: async () => ({ offset: 0, length: value.length, totalSize: value.length, contentBase64: btoa(value) }) })
  try {
    await flush()
    for (let n = 0; n < 12; n++) {
      value = `fresh-${n}\n`
      viewer.sockets.at(-1).frame({ type: 'error', code: 'FILE_CHANGED' }); await flush(); await flush()
      const blocks = viewer.state.renderedBlocks.value
      assert.equal(new Set(blocks.map(b => b.key)).size, blocks.length)
      assert.ok(blocks.length <= 16)
      assert.equal(viewer.state.rangeEnd.value, value.length)
      assert.ok(viewer.state.combinedText.value.endsWith(`===================truncated===================\n${value}`))
    }
    await viewer.state.copyDisplayed()
    assert.equal(viewer.copied[0], viewer.state.combinedText.value)
  } finally { viewer.unmount() }
})

test('filtered reset retains old results during failed reload and separates repeated line numbers', async () => {
  let scan = 0, fail = false
  const viewer = mountViewer({ api: {
    sftpFilterStart: async () => { if (fail) throw new Error('No such file'); return { scanId: `s${++scan}` } },
    historyPage: async ({ scanId }) => ({ rows: [{ lineNumber: 1, text: scanId, matched: true }], offset: 3, totalSize: 3, eof: true })
  } })
  try {
    await flush(); viewer.state.searchKeyword.value = 'x'; await startWholeFilter(viewer); await flush()
    fail = true; viewer.sockets.at(-1).frame({ type: 'error', code: 'FILE_CHANGED' }); await flush()
    assert.equal(viewer.state.displayedText.value, 's1')
    fail = false; viewer.state.setFollowing(false); viewer.state.setFollowing(true); await flush(); await flush()
    assert.equal(viewer.state.displayedText.value, 's1\n===================truncated===================\ns2')
    viewer.sockets.at(-1).frame({ type: 'data', sequence: 1, scanId: 's2', rows: [{ lineNumber: 1, text: 's2 appended', matched: true }], offset: 12, totalSize: 12 })
    await flush()
    assert.equal(viewer.state.displayedText.value, 's1\n===================truncated===================\ns2 appended')
    const blocks = viewer.state.renderedBlocks.value
    assert.equal(new Set(blocks.map(b => b.key)).size, blocks.length)
    assert.deepEqual(viewer.messages, [])
  } finally { viewer.unmount() }
})


test('one entered keyword drives search and filtering while active recovery keeps its applied keyword', async () => {
  const keywords = []
  const viewer = mountViewer({ api: { sftpFilterStart: async data => { keywords.push(data.keyword); return { scanId: `s${keywords.length}` } } } })
  try {
    await flush(); viewer.state.searchKeyword.value = 'hello'
    assert.equal(viewer.state.matchCount.value, 1)
    await startWholeFilter(viewer); await flush()
    assert.deepEqual(keywords, ['hello'])
    viewer.state.searchKeyword.value = 'other'
    viewer.sockets.at(-1).frame({ type: 'error', code: 'FILE_CHANGED' }); await flush(); await flush()
    assert.deepEqual(keywords, ['hello', 'hello'])
    await startWholeFilter(viewer); await flush()
    assert.deepEqual(keywords, ['hello', 'hello', 'other'])
  } finally { viewer.unmount() }
})


test('terminal-style search scope uses Enter for find or file filtering and Shift+Enter for previous match', async () => {
  const keywords = []
  const viewer = mountViewer({ api: { sftpFilterStart: async data => { keywords.push(data.keyword); return { scanId: 's' } } } })
  try {
    await flush(); viewer.state.searchKeyword.value = 'l'; await flush()
    viewer.state.activeMatch.value = 0
    await viewer.state.onSearchEnter({ shiftKey: false })
    assert.equal(viewer.state.activeMatch.value, 1)
    await viewer.state.onSearchEnter({ shiftKey: true })
    assert.equal(viewer.state.activeMatch.value, 0)
    assert.deepEqual(keywords, [])
    viewer.state.searchMode.value = 'filter'
    await viewer.state.onSearchEnter({ shiftKey: false })
    assert.deepEqual(keywords, ['l'])
    assert.equal(viewer.state.filter.active.value, true)
    viewer.state.closeSearchTools()
    assert.equal(viewer.state.toolsVisible.value, false)
    assert.equal(viewer.state.filter.active.value, true)
  } finally { viewer.unmount() }
})

test('Ctrl+F inside the search input advances a match without invoking browser find', async () => {
  const viewer = mountViewer(); const calls = []
  try {
    await flush(); viewer.state.searchKeyword.value = 'l'; await flush()
    viewer.state.toolsVisible.value = true; viewer.state.activeMatch.value = 0
    const input = { focus: () => calls.push('focus'), select: () => calls.push('select') }
    viewer.state.searchInputRef.value = input
    viewer.listeners.keydown({ key: 'f', ctrlKey: true, target: viewer.state.searchInputRef.value, preventDefault: () => calls.push('prevent'), stopPropagation: () => calls.push('stop') })
    await flush()
    assert.equal(viewer.state.activeMatch.value, 1)
    assert.deepEqual(calls, ['prevent', 'stop'])
  } finally { viewer.unmount() }
})


test('historical filtering waits for complete before live Tail and upward reading resumes the same history socket at bottom', async () => {
  const viewer = mountViewer({ automaticHistory: false })
  try {
    await flush(); viewer.state.searchKeyword.value = 'x'
    const running = startWholeFilter(viewer); await flush()
    const history = viewer.sockets.at(-1)
    assert.equal(history.sent[0].historical, true)
    viewer.body.scrollTop = 0; viewer.body.scrollHeight = 500; viewer.state.onViewerScroll()
    history.frame({ type: 'data', sequence: 1, scanId: 'a', rows: [{ lineNumber: 1, text: 'x' }], offset: 2, totalSize: 2, eof: true }); await flush()
    assert.deepEqual(viewer.state.filter.rows.value, []); assert.equal(history.sent.at(-1).type, 'pause')
    await viewer.state.goBottom(); await flush()
    assert.equal(viewer.sockets.at(-1), history); assert.deepEqual(history.sent.at(-1), { type: 'ack', sequence: 1 })
    assert.equal(viewer.state.canFilterTail.value, false); assert.equal(viewer.state.autoFollow.value, false)
    history.frame({ type: 'complete' }); await running; await flush()
    assert.equal(viewer.state.autoFollow.value, true); assert.equal(viewer.sockets.at(-1).sent[0].historical, undefined)
  } finally { viewer.unmount() }
})

test('canceling historical filtering resolves the awaiting viewer operation without a server response', async () => {
  const viewer = mountViewer({ automaticHistory: false })
  try {
    await flush(); viewer.state.searchKeyword.value = 'x'
    const running = startWholeFilter(viewer); await flush()
    await viewer.state.cancelFilter(); await running
    assert.equal(viewer.state.filter.canceled.value, true); assert.equal(viewer.state.autoFollow.value, false)
  } finally { viewer.unmount() }
})

test('historical server errors preserve recovery codes and rebuild the filter before live Tail', async () => {
  let scans = 0
  const viewer = mountViewer({ automaticHistory: false, api: { sftpFilterStart: async () => ({ scanId: `s${++scans}`, mode: 'sftp' }) } })
  try {
    await flush(); viewer.state.searchKeyword.value = 'x'
    const run = startWholeFilter(viewer); await flush()
    assert.match(viewer.state.filterStatus.value, /SFTP兼容扫描/)
    viewer.sockets.at(-1).frame({ type: 'error', code: 'FILE_CHANGED', message: 'changed' }); await run; await flush()
    assert.equal(scans, 2); assert.equal(viewer.state.recovering.value, true)
    const socket = viewer.sockets.at(-1)
    socket.frame({ type: 'data', sequence: 1, scanId: 's2', rows: [{ lineNumber: 1, text: 'x' }], offset: 1, totalSize: 1, eof: true }); await flush()
    assert.equal(viewer.state.autoFollow.value, true, 'recovery retains intent while waiting for complete')
    assert.equal(viewer.state.canFilterTail.value, false)
    socket.frame({ type: 'complete' }); await flush()
    assert.equal(viewer.state.recovering.value, false); assert.equal(viewer.state.autoFollow.value, true)
    assert.deepEqual(viewer.messages, [])
  } finally { viewer.unmount() }
})

test('upward reading during historical recovery pauses and resumes its current scan without restarting', async () => {
  let scans = 0
  const viewer = mountViewer({ automaticHistory: false, api: { sftpFilterStart: async () => ({ scanId: `s${++scans}`, mode: 'remote' }) } })
  try {
    await flush(); viewer.state.searchKeyword.value = 'x'
    const first = startWholeFilter(viewer); await flush()
    viewer.sockets.at(-1).frame({ type: 'error', code: 'FILE_CHANGED', message: 'reset' }); await first; await flush()
    const history = viewer.sockets.at(-1)
    viewer.body.scrollTop = 0; viewer.body.scrollHeight = 500; viewer.state.onViewerScroll()
    history.frame({ type: 'data', sequence: 1, scanId: 's2', rows: [{ lineNumber: 1, text: 'held' }], offset: 4, totalSize: 4, eof: true }); await flush()
    assert.deepEqual(viewer.state.filter.rows.value, []); assert.equal(history.sent.at(-1).type, 'pause')
    await viewer.state.goBottom(); await flush()
    assert.equal(viewer.sockets.at(-1), history); assert.deepEqual(history.sent.at(-1), { type: 'ack', sequence: 1 })
    history.frame({ type: 'complete' }); await flush(); await flush()
    assert.equal(scans, 2); assert.equal(viewer.state.recovering.value, false); assert.equal(viewer.state.autoFollow.value, true)
  } finally { viewer.unmount() }
})

test('current-content filtering consumes only raw cache and keeps an existing normal Tail', async () => {
  const reads = [], scans = [], cancels = []
  const viewer = mountViewer({ read: async (...args) => { reads.push(args); return { offset: 0, length: 19, totalSize: 19, contentBase64: btoa('before\nhit partial') } },
    api: { sftpFilterStart: async data => { scans.push(data); return { scanId: 'remote' } }, sftpFilterCancel: async data => cancels.push(data) } })
  try {
    await flush();const socket = viewer.sockets[0], io = reads.length, commands = socket.sent.length
    viewer.state.searchKeyword.value = 'hit'; viewer.state.searchMode.value = 'local'; await flush()
    assert.equal(viewer.state.localMode.value, true)
    assert.equal(viewer.state.autoFollow.value, true)
    assert.deepEqual(viewer.state.localFilter.rows.value.map(r => r.text), ['hit partial'])
    assert.equal(reads.length, io); assert.equal(scans.length, 0); assert.equal(cancels.length, 0)
    assert.equal(viewer.sockets.length, 1); assert.equal(socket.sent.length, commands)
    socket.frame({ type: 'data', sequence: 1, content: ' done\nmiss\nhit next\n', offset: 42, totalSize: 42 }); await flush()
    assert.deepEqual(viewer.state.localFilter.rows.value.map(r => r.text), ['hit partial done', 'hit next'])
    assert.equal(viewer.state.combinedText.value, 'before\nhit partial done\nmiss\nhit next\n')
    assert.equal(reads.length, io); assert.equal(scans.length, 0)
  } finally { viewer.unmount() }
})

test('local filtering never starts a paused Tail or reads history when going to result bottom', async () => {
  const viewer = mountViewer()
  try {
    await flush();viewer.state.setFollowing(false);const sockets = viewer.sockets.length, sent = viewer.sockets[0].sent.length
    viewer.body.scrollTop = 31;viewer.state.searchMode.value = 'local';viewer.state.searchKeyword.value = 'hello';await flush()
    await viewer.state.goBottom();viewer.state.onViewerScroll();await flush()
    assert.equal(viewer.state.autoFollow.value, false);assert.equal(viewer.sockets.length,sockets);assert.equal(viewer.sockets[0].sent.length,sent)
    viewer.state.searchKeyword.value = 'absent';await flush();assert.equal(viewer.state.localFilter.rows.value.length,0)
    viewer.state.searchKeyword.value = 'hello';await flush();assert.equal(viewer.state.localFilter.rows.value.length,1)
    await viewer.state.leaveFilter();assert.equal(viewer.state.searchMode.value,'find');assert.equal(viewer.body.scrollTop,31)
    assert.equal(viewer.state.combinedText.value,'hello\n')
  } finally { viewer.unmount() }
})

test('whole-file scanning requires explicit selection and start, while mode switches cancel exactly once', async () => {
  const starts = [], cancels = [];const viewer = mountViewer({ api: { sftpFilterStart: async data => {starts.push(data);return {scanId:'s'}},sftpFilterCancel: async data => cancels.push(data) } })
  try {
    await flush();viewer.state.searchKeyword.value='hello';await flush();await viewer.state.startFilter();assert.equal(starts.length,0)
    viewer.state.searchMode.value='filter';await flush();assert.equal(starts.length,0)
    await viewer.state.startFilter();assert.equal(starts.length,1)
    viewer.state.searchKeyword.value='other';await flush();assert.equal(starts.length,1)
    viewer.state.searchMode.value='local';await flush();assert.equal(cancels.length,1);assert.equal(viewer.state.filter.active.value,false)
    assert.equal(viewer.state.localFilter.rows.value.length,0)
    viewer.state.searchKeyword.value='hello';await flush();assert.equal(viewer.state.localFilter.rows.value.length,1)
    assert.equal(starts.length,1)
  } finally { viewer.unmount() }
})

test('leaving a whole-file start in flight cancels its late cursor without opening a history socket', async () => {
  let resolve;const response = new Promise(r => { resolve = r });const canceled = []
  const viewer = mountViewer({ api: { sftpFilterStart: () => response, sftpFilterCancel: async data => canceled.push(data) } })
  try {
    await flush();viewer.state.searchKeyword.value='hello';viewer.state.searchMode.value='filter';await flush()
    const run = viewer.state.startFilter();await flush();const oldSocketCount=viewer.sockets.length
    viewer.state.searchMode.value='local';await flush();await run
    resolve({ scanId:'late' });await flush()
    assert.equal(viewer.sockets.length,oldSocketCount);assert.equal(canceled.length,1)
    assert.equal(viewer.state.filter.active.value,false);assert.equal(viewer.state.localFilter.rows.value[0].text,'hello')
  } finally { viewer.unmount() }
})

test('local refresh and encoding controls do not request additional file bytes', async () => {
  let reads=0;const viewer=mountViewer({read:async()=>{reads++;return {offset:0,length:6,totalSize:6,contentBase64:btoa('hello\n')}}})
  try {
    await flush();viewer.state.setFollowing(false);viewer.state.searchKeyword.value='hello';viewer.state.searchMode.value='local';await flush();const baseline=reads
    await viewer.state.refreshViewer();await viewer.state.changeEncoding();await viewer.state.jumpToHead();await viewer.state.loadLater();await flush()
    assert.equal(reads,baseline);assert.equal(viewer.state.localFilter.rows.value.length,1)
  } finally {viewer.unmount()}
})

test('pending whole-file query options never change its recovery scan until explicit restart', async () => {
  const starts=[];const viewer=mountViewer({api:{sftpFilterStart:async data=>{starts.push(data);return {scanId:`s${starts.length}`}}}})
  try {
    await flush();viewer.state.searchKeyword.value='hello';viewer.state.caseSensitive.value=true;await startWholeFilter(viewer)
    viewer.state.searchKeyword.value='other';viewer.state.caseSensitive.value=false;viewer.state.withContext.value=true;await flush()
    assert.match(viewer.state.filterStatus.value,/尚未重新扫描/)
    viewer.sockets.at(-1).frame({type:'error',code:'FILE_CHANGED'});await flush();await flush()
    assert.equal(starts.length,2);assert.equal(starts[1].keyword,'hello');assert.equal(starts[1].caseSensitive,true);assert.equal(starts[1].contextLines,0)
  } finally {viewer.unmount()}
})

test('a normal read finishing after local mode entry can populate cache without starting Tail', async () => {
  let resolve;const waiting = new Promise(r => {resolve=r})
  const viewer=mountViewer({read:()=>waiting})
  try {
    await flush();assert.equal(viewer.sockets.length,0)
    viewer.state.searchMode.value='local';viewer.state.searchKeyword.value='hit';await flush()
    resolve({offset:0,length:4,totalSize:4,contentBase64:btoa('hit\n')});await flush();await flush()
    assert.equal(viewer.sockets.length,0);assert.equal(viewer.state.autoFollow.value,false)
    assert.deepEqual(viewer.state.localFilter.rows.value.map(r=>r.text),['hit'])
  } finally {viewer.unmount()}
})

test('dropdown exits from local restore the original raw reading position before any scan', async () => {
  for(const next of ['find','filter']) {
    const viewer=mountViewer()
    try {
      await flush();viewer.state.setFollowing(false);viewer.body.scrollTop=73
      viewer.state.searchMode.value='local';viewer.state.searchKeyword.value='hello';await flush()
      viewer.body.scrollTop=0;viewer.state.searchMode.value=next;await flush()
      assert.equal(viewer.body.scrollTop,73);assert.equal(viewer.state.filter.active.value,false)
    } finally {viewer.unmount()}
  }
})
