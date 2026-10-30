import test from 'node:test'
import assert from 'node:assert/strict'
import { rdpProfileForm, rdpProfilePayload } from '../src/utils/rdpProfile.js'

test('new RDP profile has its own port and no copied SSH credentials', () => {
  assert.deepEqual(rdpProfileForm(), { name: '', username: '', domain: '', password: '', port: 3389, enabled: 1, ignoreCertificate: false })
})
test('editing preserves a stored password by omitting an empty replacement', () => {
  const form = rdpProfileForm({ id: 2, hostId: 4, name: 'Office', username: 'alice', domain: 'ACME', port: 3390, hasPassword: true, password: 'must-not-copy', enabled: 1 })
  assert.equal(form.password, '')
  assert.deepEqual(rdpProfilePayload(form, 4, 2), { hostId: 4, name: 'Office', username: 'alice', domain: 'ACME', port: 3390, enabled: 1, ignoreCertificate: false })
})
test('create requires a password and valid independent RDP port', () => {
  assert.throws(() => rdpProfilePayload({ ...rdpProfileForm(), name: 'PC', username: 'u' }, 4), /密码/)
  assert.throws(() => rdpProfilePayload({ ...rdpProfileForm(), name: 'PC', username: 'u', password: 'p', port: 65536 }, 4), /端口/)
  assert.throws(() => rdpProfilePayload({ ...rdpProfileForm(), name: ' ', username: 'u', password: 'p' }, 4), /名称/)
})
test('password replacements preserve whitespace while names are trimmed', () => {
  assert.deepEqual(rdpProfilePayload({ ...rdpProfileForm(), name: ' PC ', username: ' user ', password: ' p ' }, 4), { hostId: 4, name: 'PC', username: 'user', domain: '', password: ' p ', port: 3389, enabled: 1, ignoreCertificate: false })
})
