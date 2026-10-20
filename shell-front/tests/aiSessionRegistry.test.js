import test from 'node:test'
import assert from 'node:assert/strict'
import { aiSessionOwner, createAiSessionRegistry } from '../src/utils/aiSessionRegistry.js'

test('AI sessions are isolated by account, including accounts on the same host', () => {
  const registry = createAiSessionRegistry()
  const first = { hostId: 8, accountId: 21 }
  const second = { hostId: 8, accountId: 22 }
  registry.set(first, { conversationId: 'first', events: [{ text: 'one' }] })
  registry.set(second, { conversationId: 'second', events: [{ text: 'two' }] })
  assert.equal(registry.get(first).conversationId, 'first')
  assert.equal(registry.get(second).conversationId, 'second')
})

test('saving a server session snapshots its event collections and replaces that server latest session', () => {
  const registry = createAiSessionRegistry()
  const server = { hostId: 8, accountId: 21 }
  const events = [{ text: 'old' }]
  registry.set(server, { conversationId: 'older', events })
  events.push({ text: 'mutated after save' })
  registry.set(server, { conversationId: 'latest', events: [{ text: 'new' }] })
  assert.deepEqual(registry.get(server), { conversationId: 'latest', events: [{ text: 'new' }], pending: [], attachments: [] })
})

test('host-only and unassigned contexts have separate owners', () => {
  assert.equal(aiSessionOwner({ hostId: 8 }), 'host:8')
  assert.equal(aiSessionOwner(null), 'unassigned')
  assert.notEqual(aiSessionOwner({ hostId: 8 }), aiSessionOwner({ hostId: 8, accountId: 21 }))
})
