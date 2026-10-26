import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveSshExpandedKeys } from '../src/utils/sshConsoleStorage.js'

test('resolveSshExpandedKeys: handles null and empty selections', async () => {
  assert.deepEqual(await resolveSshExpandedKeys(null), [])
  assert.deepEqual(await resolveSshExpandedKeys(undefined), [])
})

test('resolveSshExpandedKeys: root group (parentId: null) returns empty array', async () => {
  const group = { nodeType: 'GROUP', id: 1, parentId: null }
  const res = await resolveSshExpandedKeys(group)
  assert.deepEqual(res, [])
})

test('resolveSshExpandedKeys: nested group returns ancestor group keys', async () => {
  const groups = {
    1: { id: 1, parentId: null },
    2: { id: 2, parentId: 1 },
    3: { id: 3, parentId: 2 }
  }
  const getGroup = async (id) => groups[id]

  // Subgroup with parentId 2 (child of 1)
  const group3 = { nodeType: 'GROUP', id: 3, parentId: 2 }
  const res = await resolveSshExpandedKeys(group3, { getGroup })
  assert.deepEqual(res, ['g:1', 'g:2'])
})

test('resolveSshExpandedKeys: group without parentId field fetches parent via getGroup', async () => {
  const groups = {
    10: { id: 10, parentId: 5 },
    5: { id: 5, parentId: null }
  }
  const getGroup = async (id) => groups[id]

  const group10 = { nodeType: 'GROUP', id: 10 }
  const res = await resolveSshExpandedKeys(group10, { getGroup })
  assert.deepEqual(res, ['g:5'])
})

test('resolveSshExpandedKeys: host returns ancestor groups and host key', async () => {
  const groups = {
    1: { id: 1, parentId: null },
    2: { id: 2, parentId: 1 }
  }
  const getGroup = async (id) => groups[id]

  const host = { nodeType: 'HOST', id: 100, groupId: 2 }
  const res = await resolveSshExpandedKeys(host, { getGroup })
  assert.deepEqual(res, ['g:1', 'g:2', 'h:100'])
})

test('resolveSshExpandedKeys: host with extra.groupId uses it without getHost', async () => {
  const groups = {
    2: { id: 2, parentId: null }
  }
  const getGroup = async (id) => groups[id]

  const host = { nodeType: 'HOST', id: 100, extra: { groupId: 2 } }
  const res = await resolveSshExpandedKeys(host, { getGroup })
  assert.deepEqual(res, ['g:2', 'h:100'])
})

test('resolveSshExpandedKeys: account returns host and all ancestor groups', async () => {
  const groups = {
    1: { id: 1, parentId: null },
    2: { id: 2, parentId: 1 }
  }
  const hosts = {
    100: { id: 100, groupId: 2 }
  }
  const getGroup = async (id) => groups[id]
  const getHost = async (id) => hosts[id]

  const account = { nodeType: 'ACCOUNT', id: 999, hostId: 100 }
  const res = await resolveSshExpandedKeys(account, { getGroup, getHost })
  assert.deepEqual(res, ['g:1', 'g:2', 'h:100'])
})

test('resolveSshExpandedKeys: guards against cyclic parent references', async () => {
  const groups = {
    1: { id: 1, parentId: 2 },
    2: { id: 2, parentId: 1 }
  }
  const getGroup = async (id) => groups[id]

  const group = { nodeType: 'GROUP', id: 1, parentId: 2 }
  const res = await resolveSshExpandedKeys(group, { getGroup })
  assert.ok(Array.isArray(res))
  assert.equal(res.length, 2)
})
