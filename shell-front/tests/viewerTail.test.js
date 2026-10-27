import test from 'node:test'
import assert from 'node:assert/strict'
import { createViewerTail } from '../src/composables/useViewerTail.js'

function setup(onData = () => true) {
  const sockets = [], timers = [], errors = []
  const tail = createViewerTail({ url: () => 'ws://localhost/ws/ssh-console/file-tail',
    connect: () => { const s = { readyState: 0, sent: [], send(v) { this.sent.push(JSON.parse(v)) }, close() { this.readyState = 3 }, open() { this.readyState = 1; this.onopen() }, frame(v) { this.onmessage({ data: JSON.stringify(v) }) } }; sockets.push(s); return s },
    schedule: fn => { timers.push(fn); return fn }, unschedule: fn => { const i = timers.indexOf(fn); if (i >= 0) timers.splice(i, 1) },
    onData, onError: e => errors.push(e) })
  tail.start({ sessionToken: 'secret', path: '/a', offset: 10 })
  sockets[0].open(); sockets[0].frame({ type: 'ready', tailId: 't' })
  return { tail, sockets, timers, errors }
}
const flush = () => new Promise(setImmediate)
test('acknowledges applied batches once and deduplicates replay', async () => {
  const applied = [], s = setup(page => { applied.push(page.content); return true })
  s.sockets[0].frame({ type: 'data', sequence: 1, content: 'hi', offset: 12 })
  await flush()
  assert.deepEqual(applied, ['hi'])
  assert.deepEqual(s.sockets[0].sent.at(-1), { type: 'ack', sequence: 1 })
  s.sockets[0].frame({ type: 'data', sequence: 1, content: 'hi', offset: 12 }); await flush()
  assert.deepEqual(applied, ['hi']); s.tail.close()
})
test('selection pause holds a single batch without applying or acknowledging it', async () => {
  const applied = [], s = setup(page => { applied.push(page.content); return true })
  s.tail.pause(); s.sockets[0].frame({ type: 'data', sequence: 1, content: 'held' }); await flush()
  assert.deepEqual(applied, [])
  assert.equal(s.sockets[0].sent.at(-1).type, 'pause')
  s.tail.resume(); await flush()
  assert.deepEqual(applied, ['held']); assert.equal(s.sockets[0].sent.at(-1).type, 'ack'); s.tail.close()
})
test('reconnect resumes the retained subscription with the last applied sequence', async () => {
  const s = setup(); s.sockets[0].frame({ type: 'data', sequence: 1 }); await flush()
  s.sockets[0].onclose(); s.timers.shift()(); s.sockets[1].open()
  assert.deepEqual(s.sockets[1].sent[0], { type: 'resume', sessionToken: 'secret', tailId: 't', sequence: 1 })
  s.tail.close(); assert.equal(s.timers.length, 0)
})
test('close rejects stale frames and terminal errors stop reconnection', async () => {
  const applied = [], s = setup(page => { applied.push(page); return true })
  const old = s.sockets[0]; s.tail.close(); old.frame({ type: 'data', sequence: 1 }); await flush()
  assert.deepEqual(applied, []); assert.equal(old.sent.at(-1).type, 'close')
  const t = setup(); t.sockets[0].frame({ type: 'error', message: '文件已改变' }); t.sockets[0].onclose()
  assert.deepEqual(t.errors, ['文件已改变']); assert.equal(t.timers.length, 0)
})

test('a lost acknowledgement is resumed without applying the batch twice', async () => {
  const applied = [], s = setup(page => { applied.push(page.content); return true })
  s.sockets[0].frame({ type: 'data', sequence: 1, content: 'once' }); await flush()
  s.sockets[0].onclose(); s.timers.shift()(); s.sockets[1].open()
  s.sockets[1].frame({ type: 'ready', tailId: 't' })
  s.sockets[1].frame({ type: 'data', sequence: 1, content: 'once' }); await flush()
  assert.deepEqual(applied, ['once'])
  assert.deepEqual(s.sockets[1].sent.at(-1), { type: 'ack', sequence: 1 }); s.tail.close()
})

test('a replaced subscription cannot apply old socket data to a new file', async () => {
  const applied = [], s = setup(page => { applied.push(page.content); return true })
  s.tail.start({ sessionToken: 'secret', path: '/b', offset: 0 })
  s.sockets[0].frame({ type: 'data', sequence: 1, content: 'old' }); await flush()
  assert.deepEqual(applied, []); s.tail.close()
})

test('sequence gaps and missing subscription IDs stop the stream', async () => {
  const s = setup(); s.sockets[0].frame({ type: 'data', sequence: 2 })
  assert.equal(s.errors.length, 1)
  assert.equal(s.sockets[0].sent.some(f => f.type === 'ack'), false)
  const t = setup(); t.sockets[0].frame({ type: 'ready' })
  assert.equal(t.errors.length, 1)
})

test('an asynchronous application spanning reconnect is committed once on the current socket', async () => {
  let finish, applications = 0
  const s = setup(() => { applications++; return new Promise(resolve => { finish = resolve }) })
  s.sockets[0].frame({ type: 'data', sequence: 1 })
  s.sockets[0].onclose(); s.timers.shift()(); s.sockets[1].open()
  s.sockets[1].frame({ type: 'ready', tailId: 't' }); s.sockets[1].frame({ type: 'data', sequence: 1 })
  finish(true); await flush()
  assert.equal(applications, 1)
  assert.deepEqual(s.sockets[1].sent.at(-1), { type: 'ack', sequence: 1 }); s.tail.close()
})

test('a result-limit batch is acknowledged then closed without reconnecting', async () => {
  const s = setup(); s.sockets[0].frame({ type: 'data', sequence: 1, limited: true }); await flush()
  assert.deepEqual(s.sockets[0].sent.slice(-2).map(frame => frame.type), ['ack', 'close'])
  s.sockets[0].onclose(); assert.equal(s.timers.length, 0); assert.deepEqual(s.errors, [])
})


test('recoverable file and session errors hand off once without terminal error notifications', async () => {
  const sockets = [], recovery = [], errors = []
  const tail = createViewerTail({ url: () => 'ws://local', onData: () => true,
    connect: () => { const socket = { readyState: 1, send() {}, close() {} }; sockets.push(socket); return socket },
    onRecover: frame => recovery.push(frame.code), onError: message => errors.push(message) })
  tail.start({ sessionToken: 'token', path: '/app.log', offset: 10 })
  const message = { data: JSON.stringify({ type: 'error', code: 'FILE_CHANGED', message: 'changed' }) }
  sockets[0].onmessage(message); sockets[0].onmessage(message)
  assert.deepEqual(recovery, ['FILE_CHANGED'])
  assert.deepEqual(errors, [])
  tail.close()
})
