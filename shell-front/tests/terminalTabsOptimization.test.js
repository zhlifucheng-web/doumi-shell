import test from 'node:test'
import assert from 'node:assert/strict'
import {
  formatTerminalTabDisplayLabel,
  saveTerminalTabs,
  readSavedTerminalTabs,
  getTabUsername,
  getTabMiniHost,
  getTabThemeStyle
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

test('getTabUsername: extracts logged-in account username cleanly', () => {
  assert.equal(getTabUsername({ account: { username: 'root' } }), 'root')
  assert.equal(getTabUsername({ account: { username: 'nginx' } }), 'nginx')
  assert.equal(getTabUsername({ account: null }), 'user')
})

test('getTabMiniHost: produces concise 2-4 char host identifier for collapsed mini dock', () => {
  // Custom title takes priority
  assert.equal(getTabMiniHost({ customTitle: '生产网关' }), '生产网')
  assert.equal(getTabMiniHost({ customTitle: 'GW' }), 'GW')

  // IP addresses take the last octet
  assert.equal(getTabMiniHost({ account: { name: '192.168.10.150' } }), '.150')
  assert.equal(getTabMiniHost({ account: { name: '10.0.0.8' } }), '.8')

  // English words take up to 4 characters
  assert.equal(getTabMiniHost({ account: { name: 'Web1' } }), 'Web1')
  assert.equal(getTabMiniHost({ account: { name: 'Redis-Master' } }), 'Redi')

  // Chinese names take 2-3 characters
  assert.equal(getTabMiniHost({ account: { name: '核心网关' } }), '核心')
  assert.equal(getTabMiniHost({ account: { name: '测试机' } }), '测试机')
})

test('getTabThemeStyle: returns deterministic color, border and background variables', () => {
  const style1 = getTabThemeStyle({ hostId: 101 })
  const style2 = getTabThemeStyle({ hostId: 101 })
  const style3 = getTabThemeStyle({ hostId: 102 })

  assert.ok(style1['--node-theme-color'])
  assert.ok(style1['--node-theme-border'])
  assert.ok(style1['--node-theme-bg'])
  // Deterministic for same host
  assert.equal(style1['--node-theme-color'], style2['--node-theme-color'])
})
