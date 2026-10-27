import test from 'node:test'
import assert from 'node:assert/strict'
import { createViewerSession } from '../src/composables/useViewerSession.js'

const expired = () => new Error('入参不能为空: 会话不存在或已过期')
test('concurrent expired reads share one reconnect and release only the owned connection', async () => {
  let opened = 0; const closed = [], tokens = []
  const session = createViewerSession({ token: 'shared', api: {
    context: async () => ({ accountId: 57 }), open: async id => { assert.equal(id, 57); opened++; return { sessionToken: 'owned' } }, close: async token => closed.push(token)
  } })
  const action = async token => { tokens.push(token); if (token === 'shared') throw expired(); return token }
  assert.deepEqual(await Promise.all([session.run(action), session.run(action)]), ['owned', 'owned'])
  assert.equal(opened, 1); assert.deepEqual(closed, [])
  session.close(); assert.deepEqual(closed, ['owned'])
})
test('explicit account metadata recovers a token lost across backend restart without looking up the expired token', async () => {
  const session = createViewerSession({ token: 'obsolete', accountId: 57, api: {
    context: () => assert.fail('should use account metadata'), open: async () => ({ sessionToken: 'fresh' }), close: async () => {}
  } })
  assert.equal(await session.run(async token => { if (token === 'obsolete') throw expired(); return token }), 'fresh')
  session.close()
})
test('permission errors do not create a new SSH connection or retry a failing read', async () => {
  const session = createViewerSession({ token: 'shared', accountId: 57, api: { open: () => assert.fail('permission error'), close: async () => {} } })
  let calls = 0
  await assert.rejects(session.run(async () => { calls++; throw new Error('Permission denied') }), /Permission denied/)
  assert.equal(calls, 1); session.close()
})
