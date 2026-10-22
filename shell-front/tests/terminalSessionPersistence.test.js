import test from 'node:test'
import assert from 'node:assert/strict'
import {
  allocateTerminalId,
  saveTerminalTabs,
  readSavedTerminalTabs,
  clearSavedTerminalTabs,
  SS_SSH_TERMINAL_TABS,
  SS_SSH_ACTIVE_TERMINAL_ID
} from '../src/utils/sshConsoleStorage.js'

function createMockStorage(initial = {}) {
  const store = new Map(Object.entries(initial))
  return {
    getItem(key) {
      return store.has(key) ? store.get(key) : null
    },
    setItem(key, value) {
      store.set(key, String(value))
    },
    removeItem(key) {
      store.delete(key)
    },
    clear() {
      store.clear()
    }
  }
}

test('allocateTerminalId: assigns stable default ID to primary terminal of an account', () => {
  const allocated = allocateTerminalId(101, [])
  assert.equal(allocated.tabId, 'term-101-default')
  assert.equal(allocated.terminalId, 'default')
  assert.equal(allocated.subIndex, 1)
})

test('allocateTerminalId: assigns incremental term-2, term-3 for additional tabs of the same account', () => {
  const tab1 = { accountId: 101, terminalId: 'default' }
  const allocated2 = allocateTerminalId(101, [tab1])
  assert.equal(allocated2.tabId, 'term-101-term-2')
  assert.equal(allocated2.terminalId, 'term-2')
  assert.equal(allocated2.subIndex, 2)

  const tab2 = { accountId: 101, terminalId: 'term-2' }
  const allocated3 = allocateTerminalId(101, [tab1, tab2])
  assert.equal(allocated3.tabId, 'term-101-term-3')
  assert.equal(allocated3.terminalId, 'term-3')
  assert.equal(allocated3.subIndex, 3)
})

test('allocateTerminalId: different accounts both get independent default primary sessions', () => {
  const tabA = { accountId: 101, terminalId: 'default' }
  const allocatedB = allocateTerminalId(102, [tabA])
  assert.equal(allocatedB.tabId, 'term-102-default')
  assert.equal(allocatedB.terminalId, 'default')
})

test('saveTerminalTabs and readSavedTerminalTabs: serialize and restore tabs with active tab ID', () => {
  const storage = createMockStorage()
  const tabs = [
    {
      tabId: 'term-101-default',
      terminalId: 'default',
      accountId: 101,
      hostId: 11,
      defaultCwd: '/var/www',
      label: 'app-prod-01 · root',
      encoding: 'UTF-8',
      account: { id: 101, username: 'root' },
      token: 'ephemeral-token'
    },
    {
      tabId: 'term-101-term-2',
      terminalId: 'term-2',
      accountId: 101,
      hostId: 11,
      defaultCwd: '',
      label: 'app-prod-01 · root #2',
      encoding: 'GBK',
      account: { id: 101, username: 'root' },
      token: 'another-token'
    }
  ]

  saveTerminalTabs(tabs, 'term-101-term-2', storage)

  const restored = readSavedTerminalTabs(storage)
  assert.equal(restored.tabs.length, 2)
  assert.equal(restored.activeTabId, 'term-101-term-2')

  // Verifies fields
  assert.equal(restored.tabs[0].tabId, 'term-101-default')
  assert.equal(restored.tabs[0].terminalId, 'default')
  assert.equal(restored.tabs[0].accountId, 101)
  assert.equal(restored.tabs[0].token, '') // Ephemeral tokens must reset on refresh

  assert.equal(restored.tabs[1].tabId, 'term-101-term-2')
  assert.equal(restored.tabs[1].terminalId, 'term-2')
  assert.equal(restored.tabs[1].encoding, 'GBK')

  clearSavedTerminalTabs(storage)
  const cleared = readSavedTerminalTabs(storage)
  assert.equal(cleared.tabs.length, 0)
  assert.equal(cleared.activeTabId, null)
})
