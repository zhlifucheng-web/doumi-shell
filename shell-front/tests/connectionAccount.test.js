import test from 'node:test'
import assert from 'node:assert/strict'
import { accountConnectionForm, accountConnectionPayload, accountConnectionFeedback } from '../src/utils/connectionAccount.js'

test('opening an account never copies persisted plaintext credentials into the draft', () => {
  const form = accountConnectionForm({ protocol: 'RDP', id: 3, hostId: 2, username: 'alice', password: 'leak', secret: { password: 'leak' }, port: 3390, ignoreCertificate: true })
  assert.equal(form.password, '')
  assert.equal(form.privateKeyPem, '')
  assert.equal(form.passphrase, '')
  assert.equal(form.secret, undefined)
  assert.equal(form.port, 3390)
  assert.equal(form.ignoreCertificate, true)
})

test('new RDP saves optional name as username and an explicit certificate preference', () => {
  const form = accountConnectionForm({ protocol: 'RDP', hostId: 2 })
  Object.assign(form, { username: ' alice ', password: ' p ', remark: 'office', sortOrder: 4 })
  assert.deepEqual(accountConnectionPayload(form), { hostId: 2, name: 'alice', username: 'alice', password: ' p ', domain: '', port: 3389, ignoreCertificate: false, remark: 'office', sortOrder: 4, enabled: 1 })
})

test('edited RDP tests unsaved fields while omitting a blank stored password replacement', () => {
  const form = accountConnectionForm({ protocol: 'RDP', id: 3, hostId: 2, username: 'alice', name: 'Office', enabled: 0 })
  Object.assign(form, { hostId: 8, domain: ' ACME ', port: 3390, ignoreCertificate: true })
  assert.deepEqual(accountConnectionPayload(form, { test: true }), { id: 3, hostId: 8, username: 'alice', domain: 'ACME', port: 3390, ignoreCertificate: true })
  assert.equal(accountConnectionPayload(form).password, undefined)
})

test('new tests require a host, username, credential and valid independent RDP port', () => {
  const form = accountConnectionForm({ protocol: 'RDP' })
  assert.throws(() => accountConnectionPayload(form, { test: true }), /主机/)
  form.hostId = 2
  assert.throws(() => accountConnectionPayload(form, { test: true }), /用户名/)
  form.username = 'alice'
  assert.throws(() => accountConnectionPayload(form, { test: true }), /凭据|密码/)
  form.password = 'p'; form.port = 65536
  assert.throws(() => accountConnectionPayload(form, { test: true }), /端口/)
})

test('SSH password and private-key forms preserve existing save and test contracts', () => {
  const form = accountConnectionForm({ id: 9, hostId: 2, username: ' root ', authType: 'PRIVATE_KEY_WITH_PASSPHRASE', defaultCwd: '/srv', remark: 'sudo', sortOrder: 7, enabled: 0 })
  assert.deepEqual(accountConnectionPayload(form, { test: true }), { id: 9, hostId: 2, username: 'root', authType: 'PRIVATE_KEY_WITH_PASSPHRASE' })
  Object.assign(form, { privateKeyPem: ' key ', passphrase: ' phrase ' })
  assert.deepEqual(accountConnectionPayload(form), { id: 9, hostId: 2, username: 'root', authType: 'PRIVATE_KEY_WITH_PASSPHRASE', defaultCwd: '/srv', remark: 'sudo', sortOrder: 7, enabled: 0, secret: { privateKeyPem: ' key ', passphrase: ' phrase ' } })
  Object.assign(form, { authType: 'PASSWORD', password: ' p ' })
  assert.deepEqual(accountConnectionPayload(form, { test: true }).secret, { password: ' p ' })
})

test('feedback keeps known backend guidance and timing but never returns arbitrary server text', () => {
  const draft = { password: 'secret-to-hide', privateKeyPem: '-----BEGIN PRIVATE KEY-----\nmeaningful-private-material\n-----END PRIVATE KEY-----', passphrase: 'open-sesame' }
  for (const value of ['password=secret-to-hide', 'raw secret-to-hide', 'meaningful-private-material', 'ticket=https://example.test/ws?token=opaque-ticket', 'open-sesame']) {
    assert.equal(accountConnectionFeedback(value, draft, '测试失败，请重试'), '测试失败，请重试')
  }
  assert.equal(accountConnectionFeedback('远程桌面证书验证失败，请检查证书或在此连接中选择忽略证书', draft, '失败'), '远程桌面证书验证失败，请检查证书或在此连接中选择忽略证书')
  assert.equal(accountConnectionFeedback('连接与认证成功 (123ms)', draft, '失败', { success: true }), '连接与认证成功 (123ms)')
  assert.match(accountConnectionFeedback('TLS handshake failed; password=secret-to-hide', draft, '失败'), /安全协议/)
})
