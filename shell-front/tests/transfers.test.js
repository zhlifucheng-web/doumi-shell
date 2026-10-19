import { test } from 'node:test'
import assert from 'node:assert/strict'
import { enqueueTransfer, delegateBrowserDownload, transfers, clearFinishedTransfers, transferSessionId } from '../src/composables/useTransfers.js'
import { transfersForSession } from '../src/composables/transferHistory.js'

const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
async function settled(task) {
  const deadline = Date.now() + 2000
  while (['等待中', '传输中', '取消中', '保存中'].includes(task.status)) {
    if (Date.now() > deadline) throw new Error(`Task did not finish: ${task.status}`)
    await new Promise(resolve => setImmediate(resolve))
  }
}
test('queued cancellation is immediate and never starts its transfer', async () => {
  const gate = deferred()
  const first = enqueueTransfer({ run: () => gate.promise })
  let called = false
  const second = enqueueTransfer({ run: async () => { called = true } })
  second.cancel()
  assert.equal(second.status, '已取消')
  clearFinishedTransfers()
  assert.ok(transfers.includes(first))
  assert.ok(!transfers.includes(second))
  gate.resolve()
  await settled(first)
  assert.equal(called, false)
})
test('a cancelled running operation cannot report successful completion', async () => {
  const gate = deferred()
  const task = enqueueTransfer({ run: () => gate.promise })
  task.cancel()
  gate.resolve()
  await settled(task)
  assert.equal(task.status, '已取消')
})
test('commit cannot be cancelled or removed before it finishes', async () => {
  const gate = deferred()
  let signal
  const task = enqueueTransfer({ run: async options => {
    signal = options.signal
    options.onCommitting()
    await gate.promise
  } })
  task.cancel()
  clearFinishedTransfers()
  assert.equal(signal.aborted, false)
  assert.equal(task.status, '保存中')
  assert.ok(transfers.includes(task))
  gate.resolve()
  await settled(task)
  assert.equal(task.status, '已完成')
})
test('cleanup warning remains visible after cancellation', async () => {
  const gate = deferred()
  const task = enqueueTransfer({ run: async () => {
    await gate.promise
    throw Object.assign(new Error('aborted'), { cleanupWarning: 'cleanup not confirmed' })
  } })
  task.cancel()
  gate.resolve()
  await settled(task)
  assert.equal(task.status, '已取消')
  assert.equal(task.error, 'cleanup not confirmed')
})
test('native downloads are explicitly delegated and do not block the queue', async () => {
  const gate = deferred()
  const upload = enqueueTransfer({ run: () => gate.promise })
  let launched = false
  await delegateBrowserDownload({ name: 'file', run: async () => { launched = true } })
  assert.equal(launched, true)
  const download = transfers.at(-1)
  assert.equal(download.status, '已交给浏览器')
  assert.equal(download.cancel, undefined)
  clearFinishedTransfers()
  assert.ok(!transfers.includes(download))
  gate.resolve()
  await settled(upload)
})
test('a rejected final commit explicitly requires result verification', async () => {
  const task = enqueueTransfer({ run: async options => {
    options.onCommitting()
    throw { isAxiosError: true, message: 'Network Error' }
  } })
  await settled(task)
  assert.equal(task.status, '结果待确认')
  assert.match(task.error, /提交结果未确认/)
})

test('connections of the same account and different accounts get isolated opaque session ids', () => {
  const first = transferSessionId(1, 'private-token-a')
  assert.equal(transferSessionId(1, 'private-token-a'), first)
  assert.notEqual(transferSessionId(1, 'private-token-b'), first)
  assert.notEqual(transferSessionId(2, 'private-token-a'), first)
  assert.ok(!first.includes('private-token-a'))
  assert.equal(transferSessionId(1, ''), '')
})

test('session filtering and clearing preserve other sessions and active transfers', async () => {
  const gate = deferred()
  const active = enqueueTransfer({ accountId: 1, sessionId: 'one', run: () => gate.promise })
  const waiting = enqueueTransfer({ accountId: 1, sessionId: 'two', run: async () => {} })
  await delegateBrowserDownload({ name: 'one', accountId: 1, sessionId: 'one', run: async () => {} })
  await delegateBrowserDownload({ name: 'two', accountId: 1, sessionId: 'two', run: async () => {} })
  await delegateBrowserDownload({ name: 'other', accountId: 2, sessionId: 'one', run: async () => {} })
  assert.equal(transfersForSession(transfers, 1, 'one').length, 2)
  assert.equal(transfersForSession(transfers, 1, 'two').length, 2)
  const selected = new Set(transfersForSession(transfers, 1, 'one'))
  clearFinishedTransfers(task => selected.has(task))
  assert.deepEqual(transfersForSession(transfers, 1, 'one'), [active])
  assert.equal(transfersForSession(transfers, 1, 'two').length, 2)
  assert.equal(transfersForSession(transfers, 2, 'one').length, 1)
  assert.equal(waiting.status, '等待中')
  gate.resolve()
  await settled(active)
  await settled(waiting)
})
