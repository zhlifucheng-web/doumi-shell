import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readTransferHistory, saveTransferHistory, removeTransferHistory, trimTransferHistory,
  restoreTransfer, transferSnapshot, transferErrorMessage, transfersForSession } from '../src/composables/transferHistory.js'

function memoryStorage() {
  const values = new Map()
  return { get length() { return values.size }, key: index => [...values.keys()][index],
    getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key) }
}
const record = (status = '传输中') => ({ id: 'task1', name: 'file.bin', direction: '上传',
  path: '/tmp', accountId: 1, total: 100, loaded: 40, status, error: '', createdAt: 1 })

test('reopening restores last progress as an explicit non-resumable history entry', () => {
  const storage = memoryStorage()
  saveTransferHistory(storage, record())
  const [restored] = readTransferHistory(storage)
  assert.equal(restored.loaded, 40)
  assert.equal(restored.status, '无法恢复')
  assert.equal(restored.restored, true)
  assert.match(restored.error, /无法接管或断点续传/)
  assert.equal(restored.cancel, undefined)
  // Merely opening another window must not rewrite a live task's stored state.
  assert.equal(JSON.parse(storage.getItem(storage.key(0))).status, '传输中')
})
test('a lost commit result is never reported as a failure or success', () => {
  const restored = restoreTransfer(record('保存中'))
  assert.equal(restored.status, '结果待确认')
  assert.match(restored.error, /检查远程目标或本机文件/)
})
test('completed and browser delegated tasks keep their actual status', () => {
  for (const status of ['已完成', '失败', '已取消', '已交给浏览器'])
    assert.equal(restoreTransfer(record(status)).status, status)
  assert.equal(restoreTransfer(record('交给浏览器中')).status, '结果待确认')
})
test('history never persists credentials, local file objects or handles', () => {
  const snapshot = transferSnapshot({ ...record(), sessionToken: 'secret', file: {}, handle: {}, cancel: () => {} })
  assert.ok(!('sessionToken' in snapshot))
  assert.ok(!('file' in snapshot))
  assert.ok(!('handle' in snapshot))
  assert.ok(!('cancel' in snapshot))
})

test('restored history keeps session ownership and legacy records stay separate', () => {
  const storage = memoryStorage()
  saveTransferHistory(storage, { ...record(), sessionId: 'opaque-session', sessionToken: 'secret-token' })
  saveTransferHistory(storage, { ...record(), id: 'legacy' })
  const restored = readTransferHistory(storage)
  assert.equal(transfersForSession(restored, 1, 'opaque-session').length, 1)
  assert.equal(transfersForSession(restored, 1, '')[0].id, 'legacy')
  assert.deepEqual(transfersForSession(restored, 2, 'opaque-session'), [])
  assert.deepEqual(transfersForSession(restored, null, 'opaque-session'), [])
  assert.ok(!JSON.stringify(restored).includes('secret-token'))
})
test('malformed history is ignored and only latest 200 records are retained', () => {
  const storage = memoryStorage()
  for (let i = 0; i < 205; i++) saveTransferHistory(storage, { ...record(), id: 't' + i, createdAt: i })
  trimTransferHistory(storage)
  assert.equal(storage.length, 200)
  assert.equal(readTransferHistory(storage)[0].id, 't5')
  storage.setItem('doumi-transfer-v1:bad', 'broken json')
  storage.setItem('doumi-transfer-v1:invalid', '{}')
  const history = readTransferHistory(storage)
  assert.equal(history.length, 200)
  removeTransferHistory(storage, history[0])
  assert.equal(readTransferHistory(storage).length, 199)
})
test('session, network and permission failures explain how to retry', () => {
  assert.match(transferErrorMessage(new Error('会话不存在或已过期'), '上传'), /重新连接后重新选择文件上传/)
  assert.match(transferErrorMessage({ response: { data: { externMessage: 'SSH 连接已断开' } } }, '下载'), /重新发起下载/)
  assert.match(transferErrorMessage({ isAxiosError: true, message: 'Network Error' }, '上传'), /不能自动续传/)
  assert.match(transferErrorMessage({ name: 'NotAllowedError' }, '下载'), /重新选择保存位置并授权/)
})
