import test from 'node:test'
import assert from 'node:assert/strict'
import { useViewerFilter } from '../src/composables/useViewerFilter.js'
import { createViewerTail } from '../src/composables/useViewerTail.js'

const flush = () => new Promise(setImmediate)
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function fixture(api = {}) {
  const sockets = [], timers = [], errors = [], canceled = []
  const filter = useViewerFilter({ source: () => ({ sessionToken: 'token', path: '/app.log', encoding: 'UTF-8' }),
    onError: (message, frame) => errors.push({ message, code: frame?.code }),
    api: { start: async () => ({ scanId: 'a', mode: 'remote' }), cancel: async id => canceled.push(id),
      next: () => assert.fail('history must use WebSocket'), ...api,
      stream: options => createViewerTail({ ...options, url: () => 'ws://local',
        connect: () => { const s = { readyState: 0, sent: [], send(v) { this.sent.push(JSON.parse(v)) }, close() { this.readyState = 3 }, open() { this.readyState = 1; this.onopen() }, frame(v) { this.onmessage({ data: JSON.stringify(v) }) } }; sockets.push(s); return s },
        schedule: fn => { timers.push(fn); return fn }, unschedule: fn => { const i = timers.indexOf(fn); if (i >= 0) timers.splice(i, 1) } }) } })
  async function open() { await flush(); const socket = sockets.at(-1); assert.ok(socket, 'history opens a WebSocket'); socket.open(); socket.frame({ type: 'ready', tailId: 't' }); return socket }
  function data(socket, page = {}, sequence = 1) { socket.frame({ type: 'data', sequence, scanId: 'a', rows: [], offset: 10, totalSize: 10, eof: true, ...page }) }
  async function complete(socket, page = {}) { data(socket, page); await flush(); socket.frame({ type: 'complete' }); await flush() }
  return { filter, sockets, timers, errors, canceled, open, data, complete }
}

test('historical DATA is merged and ACKed before complete releases Tail handoff', async () => {
  const s = fixture(); let settled = false
  const run = s.filter.start({ keyword: 'error' }).then(() => { settled = true }); const ws = await s.open()
  assert.deepEqual(ws.sent[0], { type: 'start', sessionToken: 'token', path: '/app.log', encoding: 'UTF-8', offset: 0, scanId: 'a', historical: true })
  s.data(ws, { rows: [{ lineNumber: 2, text: 'error' }] }); await flush()
  assert.deepEqual(s.filter.rows.value.map(r => r.text), ['error'])
  assert.deepEqual(ws.sent.at(-1), { type: 'ack', sequence: 1 })
  assert.equal(s.filter.readyForTail.value, false); assert.equal(settled, false)
  ws.frame({ type: 'complete' }); await run
  assert.equal(s.filter.readyForTail.value, true); assert.deepEqual(s.filter.tailSource(), { scanId: 'a' })
  assert.equal(ws.sent.some(f => f.type === 'close'), false)
  await s.filter.close()
})

test('empty EOF and limited history both finish only after complete', async () => {
  for (const limited of [false, true]) {
    const s = fixture(); const run = s.filter.start({ keyword: 'x' }); const ws = await s.open()
    s.data(ws, { rows: [], limited, eof: !limited }); await flush()
    assert.equal(ws.readyState, 1); assert.equal(s.filter.scanning.value, true)
    ws.frame({ type: 'complete' }); await run
    assert.equal(s.filter.scanning.value, false); assert.equal(s.filter.readyForTail.value, !limited)
    assert.equal(s.filter.limited.value, limited); await s.filter.close()
  }
})

test('pause retains an in-flight history frame without merge or ACK and resume continues the same socket', async () => {
  const s = fixture(); const run = s.filter.start({ keyword: 'x' }); const ws = await s.open()
  s.filter.pause(true); s.data(ws, { rows: [{ lineNumber: 1, text: 'held' }] }); await flush()
  assert.deepEqual(s.filter.rows.value, []); assert.equal(ws.sent.at(-1).type, 'pause')
  s.filter.pause(false); await flush()
  assert.deepEqual(s.filter.rows.value.map(r => r.text), ['held']); assert.deepEqual(ws.sent.at(-1), { type: 'ack', sequence: 1 })
  assert.equal(s.sockets.length, 1); ws.frame({ type: 'complete' }); await run; await s.filter.close()
})

test('cancel settles start immediately and ignores a late API response or old socket frames', async () => {
  const waiting = deferred(); const s = fixture({ start: () => waiting.promise })
  const run = s.filter.start({ keyword: 'x' }); await s.filter.cancel(); await run
  waiting.resolve({ scanId: 'stale' }); await flush()
  assert.deepEqual(s.canceled, ['stale']); assert.equal(s.sockets.length, 0)
  const t = fixture(); const pending = t.filter.start({ keyword: 'x' }); const ws = await t.open()
  await t.filter.cancel(); await pending; t.data(ws, { rows: [{ lineNumber: 1, text: 'stale' }] }); await flush()
  assert.deepEqual(t.filter.rows.value, []); assert.equal(t.filter.readyForTail.value, false)
})

test('reconnecting history replays the accepted sequence without duplicate rows', async () => {
  const s = fixture(); const run = s.filter.start({ keyword: 'x' }); const ws = await s.open()
  s.data(ws, { rows: [{ lineNumber: 1, text: 'once' }], eof: false }); await flush()
  const first = s.filter.rows.value[0]
  ws.onclose(); s.timers.shift()(); const next = await s.open()
  assert.deepEqual(next.sent[0], { type: 'resume', sessionToken: 'token', tailId: 't', sequence: 1 })
  s.data(next, { rows: [{ lineNumber: 1, text: 'once' }], eof: false }); await flush()
  assert.equal(s.filter.rows.value[0], first)
  s.data(next, { rows: [], eof: true }, 2); await flush(); next.frame({ type: 'complete' }); await run
  assert.equal(s.filter.rows.value.length, 1); await s.filter.close()
})

test('new generation closes its predecessor and stale completion cannot unlock Tail', async () => {
  let scan = 0; const s = fixture({ start: async () => ({ scanId: `s${++scan}` }) })
  const old = s.filter.start({ keyword: 'old' }); const ws = await s.open()
  const fresh = s.filter.start({ keyword: 'new' }); await old; const current = await s.open()
  ws.frame({ type: 'complete' }); assert.equal(s.filter.readyForTail.value, false)
  await s.complete(current, { scanId: 's2', rows: [{ lineNumber: 1, text: 'new' }] }); await fresh
  assert.deepEqual(s.filter.rows.value.map(r => r.text), ['new']); await s.filter.close()
})

test('recoverable streaming errors forward their code, settle history and prevent Tail', async () => {
  const s = fixture(); const run = s.filter.start({ keyword: 'x' }); const ws = await s.open()
  ws.frame({ type: 'error', code: 'FILE_CHANGED', message: 'reset' }); await run
  assert.deepEqual(s.errors, [{ message: 'reset', code: 'FILE_CHANGED' }]); assert.equal(s.filter.tailSource(), null)
  await s.filter.close()
})

test('filtered Tail upserts a partial row in place while retaining unrelated rows', async () => {
  const s = fixture(); const run = s.filter.start({ keyword: 'x' }); const ws = await s.open()
  await s.complete(ws, { rows: [{ lineNumber: 1, text: 'x' }, { lineNumber: 3, text: 'x partial' }] }); await run
  const rows = s.filter.rows.value, first = rows[0]
  assert.equal(s.filter.applyTail({ scanId: 'a', rows: [{ lineNumber: 3, text: 'x partial completed' }, { lineNumber: 5, text: 'x new' }], offset: 20, totalSize: 20, eof: true }), true)
  assert.equal(s.filter.rows.value, rows); assert.equal(rows[0], first)
  assert.deepEqual(rows.map(r => r.text), ['x', 'x partial completed', 'x new']); await s.filter.close()
})

test('reset preserves old results until an unpaused frame and separates repeated line numbers', async () => {
  let scan = 0; const s = fixture({ start: async () => ({ scanId: `s${++scan}` }) })
  let run = s.filter.start({ keyword: 'x' }); let ws = await s.open()
  await s.complete(ws, { scanId: 's1', rows: [{ lineNumber: 1, text: 'old' }] }); await run
  run = s.filter.start({ keyword: 'x' }, { preserveHistory: true, truncated: true }); ws = await s.open(); s.filter.pause(true)
  s.data(ws, { scanId: 's2', rows: [{ lineNumber: 1, text: 'new' }] }); await flush()
  assert.deepEqual(s.filter.displayRows.value.map(r => r.text), ['old'])
  s.filter.pause(false); await flush(); ws.frame({ type: 'complete' }); await run
  assert.deepEqual(s.filter.displayRows.value.map(r => r.text), ['old', '===================truncated===================', 'new']); await s.filter.close()
})

test('history and resets share the result cap and evict oldest history first', async () => {
  let scan = 0; const s = fixture({ start: async () => ({ scanId: `s${++scan}` }) })
  for (let n = 1; n <= 2; n++) {
    const run = s.filter.start({ keyword: 'x' }, { preserveHistory: n > 1, truncated: true }); const ws = await s.open()
    await s.complete(ws, { scanId: `s${n}`, rows: Array.from({ length: 6000 }, (_, i) => ({ lineNumber: i + 1, text: `s${n}-${i}` })) }); await run
  }
  assert.equal(s.filter.displayRows.value.length, 10000); assert.equal(s.filter.rows.value.length, 6000)
  assert.equal(s.filter.displayRows.value.at(-1).text, 's2-5999'); assert.equal(s.filter.limited.value, false)
  await s.filter.close()
})

test('the server selected remote or SFTP compatibility mode remains observable during the scan', async () => {
  for (const mode of ['remote', 'sftp']) {
    const s = fixture({ start: async () => ({ scanId: 'a', mode }) })
    const run = s.filter.start({ keyword: 'x' }); const ws = await s.open()
    assert.equal(s.filter.mode.value, mode)
    await s.complete(ws); await run; await s.filter.close()
  }
})

test('a lost complete is resumed after final ACK without reopening Tail early', async () => {
  const s = fixture(); const run = s.filter.start({ keyword: 'x' }); const ws = await s.open()
  s.data(ws, { rows: [{ lineNumber: 1, text: 'once' }] }); await flush()
  ws.onclose(); s.timers.shift()(); const next = await s.open()
  assert.equal(s.filter.readyForTail.value, false)
  assert.deepEqual(next.sent[0], { type: 'resume', sessionToken: 'token', tailId: 't', sequence: 1 })
  next.frame({ type: 'complete' }); await run
  assert.equal(s.filter.readyForTail.value, true); assert.equal(s.filter.rows.value.length, 1)
  await s.filter.close()
})

test('cancel preserves merged partial results and refuses every late frame', async () => {
  const s = fixture(); const run = s.filter.start({ keyword: 'x' }); const ws = await s.open()
  s.data(ws, { rows: [{ lineNumber: 1, text: 'keep' }], eof: false }); await flush()
  await s.filter.cancel(); await run
  s.data(ws, { rows: [{ lineNumber: 2, text: 'late' }] }, 2); ws.frame({ type: 'complete' }); await flush()
  assert.deepEqual(s.filter.rows.value.map(row => row.text), ['keep']); assert.equal(s.filter.tailSource(), null)
})

test('an operational native fallback updates the visible mode without changing row or ACK identity', async () => {
  const s=fixture({start:async()=>({scanId:'a',mode:'native'})});const run=s.filter.start({keyword:'x'}),ws=await s.open()
  s.data(ws,{rows:[{lineNumber:2,text:'x'}],mode:'sftp',reason:'原生命令资源限制，回退兼容扫描'});await flush()
  assert.equal(s.filter.mode.value,'sftp');assert.match(s.filter.reason.value,/资源限制/)
  assert.equal(s.filter.rows.value[0].lineNumber,2);assert.deepEqual(ws.sent.at(-1),{type:'ack',sequence:1})
  ws.frame({type:'complete'});await run;await s.filter.close()
})

test('normal SFTP Tail after native history does not mislabel the completed history as a fallback', async () => {
  const s=fixture({start:async()=>({scanId:'a',mode:'native'})}),run=s.filter.start({keyword:'x'}),ws=await s.open()
  await s.complete(ws,{mode:'native'});await run
  assert.equal(s.filter.applyTail({scanId:'a',rows:[],offset:20,totalSize:20,eof:true,mode:'sftp',reason:null}),true)
  assert.equal(s.filter.mode.value,'native');await s.filter.close()
})

test('initial SFTP eligibility reason remains visible when ordinary pages omit a new reason', async () => {
  const s=fixture({start:async()=>({scanId:'a',mode:'sftp',reason:'所选编码使用SFTP扫描'})}),run=s.filter.start({keyword:'x'}),ws=await s.open()
  s.data(ws,{mode:'sftp',reason:null});await flush();assert.equal(s.filter.reason.value,'所选编码使用SFTP扫描')
  ws.frame({type:'complete'});await run;await s.filter.close()
})
