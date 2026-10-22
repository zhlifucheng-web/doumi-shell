import test from 'node:test'
import assert from 'node:assert/strict'
import {
  allocateTerminalId,
  generateTerminalSessionKey,
  saveTerminalTabs,
  readSavedTerminalTabs,
  clearSavedTerminalTabs,
  getAccountSavedTerminalKey,
  saveAccountTerminalKey,
  removeAccountTerminalKey,
  LS_ACCOUNT_TERMINAL_SESSION_MAP,
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

test('generateTerminalSessionKey: produces random string starting with s_ safe for tmux and URLs', () => {
  const key1 = generateTerminalSessionKey()
  const key2 = generateTerminalSessionKey()
  assert.ok(key1.startsWith('s_'))
  assert.ok(key2.startsWith('s_'))
  assert.notEqual(key1, key2)
  assert.match(key1, /^s_[a-z0-9]+$/)
})

test('allocateTerminalId: allocates random unique session key and saves to localStorage for primary tab', () => {
  const localStore = createMockStorage()
  const allocated = allocateTerminalId(101, [], { localStorage: localStore })

  assert.ok(allocated.terminalId.startsWith('s_'))
  assert.notEqual(allocated.terminalId, 'default')
  assert.equal(allocated.tabId, `term-101-${allocated.terminalId}`)
  assert.equal(allocated.subIndex, 1)

  // Verify saved in localStorage
  const savedKey = getAccountSavedTerminalKey(101, localStore)
  assert.equal(savedKey, allocated.terminalId)

  // Subsequent call for the same account without tabs reuses the saved key
  const reconnected = allocateTerminalId(101, [], { localStorage: localStore })
  assert.equal(reconnected.terminalId, allocated.terminalId)
})

test('allocateTerminalId: assigns distinct new random keys for additional tabs of the same account', () => {
  const localStore = createMockStorage()
  const tab1 = { accountId: 101, terminalId: 's_key1' }
  const allocated2 = allocateTerminalId(101, [tab1], { localStorage: localStore })

  assert.ok(allocated2.terminalId.startsWith('s_'))
  assert.notEqual(allocated2.terminalId, 's_key1')
  assert.notEqual(allocated2.terminalId, 'default')
  assert.equal(allocated2.subIndex, 2)

  const tab2 = { accountId: 101, terminalId: allocated2.terminalId }
  const allocated3 = allocateTerminalId(101, [tab1, tab2], { localStorage: localStore })
  assert.notEqual(allocated3.terminalId, allocated2.terminalId)
  assert.notEqual(allocated3.terminalId, 's_key1')
  assert.equal(allocated3.subIndex, 3)
})

test('allocateTerminalId: different users/browsers get isolated independent random session keys', () => {
  const userAStorage = createMockStorage()
  const userBStorage = createMockStorage()

  const sessionA = allocateTerminalId(101, [], { localStorage: userAStorage })
  const sessionB = allocateTerminalId(101, [], { localStorage: userBStorage })

  // User A and User B accessing the same account MUST NOT share tmux session before sharing link
  assert.notEqual(sessionA.terminalId, sessionB.terminalId)
  assert.notEqual(sessionA.terminalId, 'default')
  assert.notEqual(sessionB.terminalId, 'default')
})

test('saveTerminalTabs and readSavedTerminalTabs: serialize, restore, and migrate legacy default keys', () => {
  const sessionStorage = createMockStorage()
  const tabs = [
    {
      tabId: 'term-101-s_abc123',
      terminalId: 's_abc123',
      accountId: 101,
      hostId: 11,
      defaultCwd: '/var/www',
      label: 'app-prod-01 · root',
      encoding: 'UTF-8',
      account: { id: 101, username: 'root' },
      token: 'ephemeral-token'
    },
    {
      tabId: 'term-101-legacy',
      terminalId: 'default', // Legacy value to be migrated
      accountId: 101,
      hostId: 11,
      defaultCwd: '',
      label: 'app-prod-01 · root #2',
      encoding: 'GBK',
      account: { id: 101, username: 'root' },
      token: 'another-token'
    }
  ]

  saveTerminalTabs(tabs, 'term-101-s_abc123', sessionStorage)

  const restored = readSavedTerminalTabs(sessionStorage)
  assert.equal(restored.tabs.length, 2)
  assert.equal(restored.activeTabId, 'term-101-s_abc123')

  // First tab preserved
  assert.equal(restored.tabs[0].tabId, 'term-101-s_abc123')
  assert.equal(restored.tabs[0].terminalId, 's_abc123')
  assert.equal(restored.tabs[0].token, '')

  // Legacy tab was upgraded to random key instead of default
  assert.ok(restored.tabs[1].terminalId.startsWith('s_'))
  assert.notEqual(restored.tabs[1].terminalId, 'default')

  clearSavedTerminalTabs(sessionStorage)
  const cleared = readSavedTerminalTabs(sessionStorage)
  assert.equal(cleared.tabs.length, 0)
  assert.equal(cleared.activeTabId, null)
})
