import test from 'node:test'
import assert from 'node:assert/strict'
import { createRdpTab, matchesConnection } from '../src/utils/connectionTabs.js'

test('same numeric SSH and RDP IDs open distinct accounts and unique desktop tabs', () => {
  const account = { id: 3, hostId: 1, username: 'Administrator', name: 'Office', ignoreCertificate: true }
  const ssh = { accountId: 3, account: { id: 3 } }
  const first = createRdpTab(account, { name: 'Windows', host: '192.0.2.1' }, [ssh])
  const second = createRdpTab(account, { name: 'Windows', host: '192.0.2.1' }, [ssh, first])
  assert.equal(matchesConnection(ssh, 3, 'RDP'), false)
  assert.equal(matchesConnection(first, 3, 'SSH'), false)
  assert.equal(matchesConnection(first, 3, 'RDP'), true)
  assert.equal(first.accountId, 'rdp:3')
  assert.equal(first.account.ignoreCertificate, true)
  assert.notEqual(first.tabId, second.tabId)
  assert.equal(second.subIndex, 2)
})

test('desktop tab labels and endpoint are independent of SSH host port', () => {
  const tab = createRdpTab({ id: 3, hostId: 1, name: 'Office', username: 'Alice', port: 3391 }, { name: 'Windows', host: '192.0.2.1', port: 2222 }, [])
  assert.equal(tab.hostAddress, '192.0.2.1')
  assert.equal(tab.account.port, 3391)
  assert.equal(tab.label, 'Windows · Office')
  assert.equal(tab.token, '')
})

test('desktop tabs can open on LAN HTTP where randomUUID is unavailable', () => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'crypto')
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: {} })
  try {
    const tab = createRdpTab({ id: 3 }, { name: 'Windows' }, [])
    assert.match(tab.tabId, /^rdp-3-/)
  } finally { Object.defineProperty(globalThis, 'crypto', previous) }
})
