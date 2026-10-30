import test from 'node:test'
import assert from 'node:assert/strict'
import { saveTerminalTabs, readSavedTerminalTabs, writeSavedSshAccount, readSavedSshAccount } from '../src/utils/sshConsoleStorage.js'

const memory = () => {
  const values = new Map()
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key), values }
}

test('RDP and SSH with identical database IDs retain separate tab identity after refresh', () => {
  const storage = memory()
  saveTerminalTabs([
    { tabId: 'ssh-7', accountId: 7, account: { id: 7, username: 'root' } },
    { tabId: 'rdp-7', accountId: 'rdp:7', protocol: 'RDP', connectionId: 7, account: { id: 7, name: 'Desktop', username: 'Administrator', port: 3391, ignoreCertificate: true } }
  ], 'rdp-7', storage)
  const result = readSavedTerminalTabs(storage)
  assert.equal(result.activeTabId, 'rdp-7')
  assert.equal(result.tabs[0].accountId, 7)
  assert.equal(result.tabs[1].accountId, 'rdp:7')
  assert.equal(result.tabs[1].protocol, 'RDP')
  assert.equal(result.tabs[1].connectionId, 7)
  assert.equal(result.tabs[1].account.ignoreCertificate, true)
  assert.equal(result.tabs[1].token, '')
})

test('persisted RDP tabs omit credentials and transient session data', () => {
  const storage = memory()
  saveTerminalTabs([{ tabId: 'rdp-2', accountId: 'rdp:2', protocol: 'RDP', connectionId: 2, token: 'transient-ticket', account: { id: 2, username: 'Administrator', password: 'private-password', secret: { password: 'private-password' }, secretEnc: 'encrypted-secret' } }], 'rdp-2', storage)
  const text = [...storage.values.values()].join('')
  assert.doesNotMatch(text, /private-password|transient-ticket|encrypted-secret/)
  assert.equal(readSavedTerminalTabs(storage).tabs[0].account.username, 'Administrator')
})

test('last selected RDP account remains typed and restores host expansion metadata', () => {
  const previous = globalThis.localStorage
  globalThis.localStorage = memory()
  try {
    writeSavedSshAccount({ nodeType: 'RDP_ACCOUNT', id: 7, key: 'r:7', hostId: 3, groupId: 1, label: 'Desktop' })
    assert.deepEqual(readSavedSshAccount(), { nodeType: 'RDP_ACCOUNT', id: 7, key: 'r:7', hostId: 3, groupId: 1, label: 'Desktop' })
  } finally { globalThis.localStorage = previous }
})
