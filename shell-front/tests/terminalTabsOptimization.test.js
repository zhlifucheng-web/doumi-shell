import test from 'node:test'
import assert from 'node:assert/strict'
import {
  formatTerminalTabDisplayLabel,
  saveTerminalTabs,
  readSavedTerminalTabs
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

test('formatTerminalTabDisplayLabel: customTitle has top priority', () => {
  const tab = {
    customTitle: '核心网关-生产排障',
    account: { name: 'Prod-GW-01', username: 'root' },
    subIndex: 2
  }
  const label = formatTerminalTabDisplayLabel(tab)
  assert.equal(label, '核心网关-生产排障')
})

test('formatTerminalTabDisplayLabel: strips redundant (IP:Port) from host name to prevent tab bloat', () => {
  const tab = {
    account: { name: '数据中台集群 (192.168.10.200:22)', username: 'admin' },
    subIndex: 1
  }
  const label = formatTerminalTabDisplayLabel(tab)
  assert.equal(label, '数据中台集群 · admin')
})

test('formatTerminalTabDisplayLabel: formats host and username cleanly', () => {
  const tab = {
    account: { name: 'Web-Node-A', username: 'nginx' },
    subIndex: 1
  }
  const label = formatTerminalTabDisplayLabel(tab)
  assert.equal(label, 'Web-Node-A · nginx')
})

test('formatTerminalTabDisplayLabel: appends subIndex when tab is secondary (#2, #3)', () => {
  const tab = {
    account: { name: 'Web-Node-A', username: 'root' },
    subIndex: 3
  }
  const label = formatTerminalTabDisplayLabel(tab)
  assert.equal(label, 'Web-Node-A · root #3')
})

test('formatTerminalTabDisplayLabel: handles fallback from legacy tab.label', () => {
  const tab = {
    label: '集群节点01 (10.0.0.1) · devops',
    subIndex: 1
  }
  const label = formatTerminalTabDisplayLabel(tab)
  assert.equal(label, '集群节点01')
})

test('saveTerminalTabs and readSavedTerminalTabs: retains customTitle and subIndex across reload', () => {
  const storage = createMockStorage()
  const tabs = [
    {
      tabId: 'term-1-s_123',
      terminalId: 's_123',
      accountId: 1,
      hostId: 10,
      customTitle: 'Nginx-Test',
      subIndex: 1,
      label: 'server-01 · root',
      account: { id: 1, username: 'root' }
    },
    {
      tabId: 'term-1-s_456',
      terminalId: 's_456',
      accountId: 1,
      hostId: 10,
      customTitle: '',
      subIndex: 2,
      label: 'server-01 · root #2',
      account: { id: 1, username: 'root' }
    }
  ]

  saveTerminalTabs(tabs, 'term-1-s_456', storage)

  const restored = readSavedTerminalTabs(storage)
  assert.equal(restored.tabs.length, 2)
  assert.equal(restored.activeTabId, 'term-1-s_456')
  assert.equal(restored.tabs[0].customTitle, 'Nginx-Test')
  assert.equal(restored.tabs[0].subIndex, 1)
  assert.equal(restored.tabs[1].customTitle, '')
  assert.equal(restored.tabs[1].subIndex, 2)
})
