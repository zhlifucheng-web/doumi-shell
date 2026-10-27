import test from 'node:test'
import assert from 'node:assert/strict'
import { useViewerFilter } from '../src/composables/useViewerFilter.js'

function fixture(api) {
  const errors = []
  const filter = useViewerFilter({ source: () => ({ sessionToken: 'token', path: '/app.log', encoding: 'UTF-8' }), onError: e => errors.push(e), api })
  return { filter, errors }
}
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }

test('whole-file scan accumulates pages and upserts a partial last line during tail', async () => {
  const pages = [
    { rows: [{ lineNumber: 2, text: 'error', matched: true }], offset: 8, totalSize: 20, eof: false },
    { rows: [{ lineNumber: 7, text: 'error last', matched: true }], offset: 20, totalSize: 20, eof: true },
    { rows: [{ lineNumber: 7, text: 'error last appended', matched: true }], offset: 29, totalSize: 29, eof: true }
  ]
  const { filter } = fixture({ start: async () => ({ scanId: 'a' }), next: async () => pages.shift(), cancel: async () => {} })
  await filter.start({ keyword: 'error', contextLines: 2, caseSensitive: false })
  assert.equal(filter.eof.value, true)
  assert.equal(filter.offset.value, 20)
  assert.deepEqual(filter.rows.value.map(r => r.lineNumber), [2, 7])
  filter.applyTail({ ...pages.shift(), scanId: 'a' })
  assert.deepEqual(filter.rows.value.map(r => r.text), ['error', 'error last appended'])
  await filter.close()
})

test('cancel ignores an in-flight page and retains previously displayed results', async () => {
  const waiting = deferred(); const canceled = []; let started
  const ready = new Promise(r => { started = r })
  const { filter } = fixture({ start: async () => ({ scanId: 'old' }), next: () => { started(); return waiting.promise }, cancel: async id => canceled.push(id) })
  const running = filter.start({ keyword: 'x' }); await ready
  await filter.cancel()
  waiting.resolve({ rows: [{ lineNumber: 1, text: 'late x' }], offset: 5, totalSize: 5, eof: true })
  await running
  assert.deepEqual(filter.rows.value, [])
  assert.equal(filter.canceled.value, true)
  assert.equal(filter.scanning.value, false)
  assert.deepEqual(canceled, ['old'])
})

test('a start response arriving after close is canceled and never repaints', async () => {
  const waiting = deferred(); const canceled = []
  const { filter } = fixture({ start: () => waiting.promise, next: () => assert.fail('stale scan'), cancel: async id => canceled.push(id) })
  const running = filter.start({ keyword: 'x' })
  await filter.close()
  waiting.resolve({ scanId: 'stale' }); await running
  assert.equal(filter.active.value, false)
  assert.deepEqual(canceled, ['stale'])
})

test('limited results and errors stop further tail requests', async () => {
  let calls = 0
  const { filter } = fixture({ start: async () => ({ scanId: 'a' }), next: async () => { calls++; return { rows: [], offset: 10, totalSize: 100, limited: true, eof: false } }, cancel: async () => {} })
  await filter.start({ keyword: 'x' }); assert.equal(filter.tailSource(), null)
  assert.equal(filter.limited.value, true)
  assert.equal(filter.eof.value, false)
  assert.equal(calls, 1)
  await filter.close()
})

test('Tail continues bounded reads after appended content spans several pages', async () => {
  let calls = 0
  const pages = [
    { rows: [], offset: 10, totalSize: 10, eof: true },
    { rows: [], offset: 20, totalSize: 40, eof: false },
    { rows: [{ lineNumber: 8, text: 'x' }], offset: 40, totalSize: 40, eof: true }
  ]
  const { filter } = fixture({ start: async () => ({ scanId: 'a' }), next: async () => { calls++; return pages.shift() }, cancel: async () => {} })
  await filter.start({ keyword: 'x' })
  filter.applyTail({ ...pages.shift(), scanId: 'a' })
  assert.equal(calls, 1, 'Tail push never issues more HTTP next requests')
  assert.equal(filter.readyForTail.value, true)
  filter.applyTail({ ...pages.shift(), scanId: 'a' })
  assert.equal(filter.rows.value[0].lineNumber, 8)
  await filter.close()
})

test('a closing old scan cannot hide a newer scan while its cancel is pending', async () => {
  const waiting = deferred(); let n = 0
  const { filter } = fixture({ start: async () => ({ scanId: ++n }), next: async () => ({ rows: [], offset: 0, totalSize: 0, eof: true }), cancel: () => waiting.promise })
  await filter.start({ keyword: 'old' })
  const closing = filter.close()
  await filter.start({ keyword: 'new' })
  waiting.resolve(); await closing
  assert.equal(filter.active.value, true)
})

test('a new scan waits for the old in-flight page and then reaches EOF with its own results', async () => {
  const oldPage = deferred(), oldStarted = deferred(); let n = 0
  const { filter } = fixture({
    start: async () => ({ scanId: ++n }),
    next: async id => { if (id === 1) { oldStarted.resolve(); return oldPage.promise }; return { rows: [{ lineNumber: 3, text: 'new result' }], offset: 5, totalSize: 5, eof: true } },
    cancel: async () => {}
  })
  const old = filter.start({ keyword: 'old' }); await oldStarted.promise
  const fresh = filter.start({ keyword: 'new' })
  oldPage.resolve({ rows: [{ lineNumber: 1, text: 'stale result' }], offset: 5, totalSize: 5, eof: true })
  await Promise.all([old, fresh])
  assert.equal(filter.eof.value, true)
  assert.equal(filter.scanning.value, false)
  assert.deepEqual(filter.rows.value.map(r => r.text), ['new result'])
  await filter.close()
})

test('Tail upserts only changed rows and keeps the existing array and row identities', async () => {
  const { filter } = fixture({ start: async () => ({ scanId: 'a' }), next: async () => ({ rows: [{ lineNumber: 1, text: 'x' }, { lineNumber: 3, text: 'x partial' }], offset: 10, totalSize: 10, eof: true }), cancel: async () => {} })
  await filter.start({ keyword: 'x' })
  const rows = filter.rows.value, first = rows[0]
  filter.applyTail({ scanId: 'a', rows: [{ lineNumber: 3, text: 'x partial completed' }, { lineNumber: 5, text: 'x new' }], offset: 20, totalSize: 20, eof: true })
  assert.equal(filter.rows.value, rows)
  assert.equal(filter.rows.value[0], first)
  assert.deepEqual(rows.map(row => row.text), ['x', 'x partial completed', 'x new'])
  await filter.close()
})


test('a historical page arriving while selected is retained until resume and applied exactly once', async () => {
  const pending = deferred(), started = deferred(); let calls = 0
  const { filter } = fixture({ start: async () => ({ scanId: 'a' }), next: () => { calls++; started.resolve(); return pending.promise }, cancel: async () => {} })
  const scan = filter.start({ keyword: 'x' }); await started.promise
  filter.pause(true)
  pending.resolve({ rows: [{ lineNumber: 1, text: 'x', matched: true }], offset: 2, totalSize: 2, eof: true })
  await scan
  assert.deepEqual(filter.rows.value, [])
  filter.pause(false); await new Promise(setImmediate)
  assert.equal(calls, 1)
  assert.deepEqual(filter.rows.value.map(r => r.text), ['x'])
  assert.equal(filter.readyForTail.value, true)
  await filter.close()
})
