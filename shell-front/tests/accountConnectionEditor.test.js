import test from 'node:test'
import assert from 'node:assert/strict'
import { nextTick } from 'vue'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'
import * as accountHelpers from '../src/utils/connectionAccount.js'

const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const settle = async () => { await Promise.resolve(); await nextTick() }
function editor(initial = {}, apis = {}) {
  const calls = [], events = []
  const request = name => async (...args) => { calls.push({ name, args }); return apis[name] ? apis[name](...args) : name.startsWith('get') ? initial : { id: 10, hostId: 2, username: 'alice', name: 'Office' } }
  const mounted = mountSfcSetup(new URL('../src/components/ssh/AccountConnectionEditor.vue', import.meta.url), {
    props: { modelValue: true, initial, hostOptions: [{ id: 2, name: 'Host', host: '192.0.2.1', port: 22 }], onSaved: value => events.push(value), 'onUpdate:modelValue': value => events.push(value) },
    modules: {
      '@/utils/connectionAccount': accountHelpers,
      '@/api/sshConsole': Object.fromEntries(['getAccount', 'createAccount', 'updateAccount', 'testAccount'].map(n => [n, request(n)])),
      '@/api/rdp': Object.fromEntries(['getRdpConnection', 'createRdpConnection', 'updateRdpConnection', 'testRdpConnection'].map(n => [n, request(n)]))
    }
  })
  return { ...mounted, calls, events }
}

test('RDP testing uses unsaved fields, blocks duplicate clicks and rejects unknown success', async () => {
  const pending = deferred()
  const e = editor({ protocol: 'RDP', hostId: 2 }, { testRdpConnection: () => pending.promise })
  try {
    Object.assign(e.state.form, { username: 'alice', password: 'p', domain: 'ACME', port: 3390, ignoreCertificate: true })
    const first = e.state.testConnection(); await e.state.testConnection()
    assert.equal(e.calls.length, 1)
    assert.deepEqual(e.calls[0], { name: 'testRdpConnection', args: [{ hostId: 2, username: 'alice', password: 'p', domain: 'ACME', port: 3390, ignoreCertificate: true }] })
    pending.resolve({ message: 'unknown' }); await first
    assert.equal(e.state.testResult.value.success, false)
    assert.equal(e.state.form.ignoreCertificate, true)
  } finally { e.unmount() }
})

test('editing fields and closing discard in-flight test feedback and clear credentials', async () => {
  const pending = deferred()
  const e = editor({ protocol: 'RDP', hostId: 2 }, { testRdpConnection: () => pending.promise })
  try {
    Object.assign(e.state.form, { username: 'alice', password: 'p' })
    const testing = e.state.testConnection()
    e.state.form.port = 3390
    pending.resolve({ success: true, message: 'connected', elapsedMs: 12 }); await testing
    assert.equal(e.state.testResult.value, null)
    e.props.modelValue = false; await settle()
    assert.equal(e.state.form.password, '')
    e.props.initial = { protocol: 'SSH', hostId: 5 }; e.props.modelValue = true; await settle()
    assert.equal(e.state.form.protocol, 'SSH')
    assert.equal(e.state.form.hostId, 5)
    assert.equal(e.state.form.username, '')
  } finally { e.unmount() }
})

test('existing protocol is locked and blank SSH credentials still test stored credentials with current fields', async () => {
  const e = editor({ protocol: 'SSH', id: 3, hostId: 2, username: 'alice', authType: 'PASSWORD' }, { testAccount: () => ({ success: true, message: 'connected' }) })
  try {
    await settle()
    e.state.form.protocol = 'RDP'; await settle()
    assert.equal(e.state.form.protocol, 'SSH')
    await e.state.testConnection()
    assert.deepEqual(e.calls.at(-1), { name: 'testAccount', args: [{ id: 3, hostId: 2, username: 'alice', authType: 'PASSWORD' }] })
    assert.equal(e.state.testResult.value.success, true)
    e.state.form.username = 'bob'
    assert.equal(e.state.testResult.value, null)
  } finally { e.unmount() }
})

test('saving emits typed account DTO and clears secrets without saving on tests', async () => {
  const e = editor({ protocol: 'RDP', hostId: 2 })
  try {
    Object.assign(e.state.form, { username: 'alice', password: 'p' })
    await e.state.saveConnection()
    assert.equal(e.calls[0].name, 'createRdpConnection')
    assert.deepEqual(e.events, [{ protocol: 'RDP', account: { id: 10, hostId: 2, username: 'alice', name: 'Office' } }, false])
    assert.equal(e.state.form.password, '')
    assert.equal(e.state.form.privateKeyPem, '')
  } finally { e.unmount() }
})

test('switching a new protocol clears credentials and stale tests cannot affect a reopened editor', async () => {
  const pending = deferred()
  const e = editor({ protocol: 'RDP', hostId: 2 }, { testRdpConnection: () => pending.promise })
  try {
    Object.assign(e.state.form, { username: 'alice', password: 'p' })
    const testing = e.state.testConnection()
    e.props.modelValue = false; await settle()
    e.props.modelValue = true; await settle()
    pending.resolve({ success: true, message: 'old' }); await testing
    assert.equal(e.state.testResult.value, null)
    Object.assign(e.state.form, { password: 'rdp', privateKeyPem: 'key', passphrase: 'phrase' })
    e.state.form.protocol = 'SSH'
    assert.equal(e.state.form.password, '')
    assert.equal(e.state.form.privateKeyPem, '')
    assert.equal(e.state.form.passphrase, '')
  } finally { e.unmount() }
})

test('test feedback never displays labelled or unlabelled draft credentials', async () => {
  const e = editor({ protocol: 'RDP', hostId: 2 }, { testRdpConnection: () => { throw new Error('password=secret-to-hide; raw draft-private-key-piece') } })
  try {
    Object.assign(e.state.form, { username: 'alice', password: 'secret-to-hide', privateKeyPem: 'BEGIN\ndraft-private-key-piece\nEND' })
    await e.state.testConnection()
    const feedback = `${e.state.errorMessage.value} ${e.state.testResult.value?.message}`
    assert.equal(feedback.includes('secret-to-hide'), false)
    assert.equal(feedback.includes('draft-private-key-piece'), false)
    assert.equal(e.state.testResult.value.success, false)
  } finally { e.unmount() }
})

test('failure or unknown test outcomes cannot display a success message', async () => {
  const e = editor({ protocol: 'RDP', hostId: 2 }, { testRdpConnection: () => ({ message: '远程桌面连接成功', elapsedMs: 23 }) })
  try {
    Object.assign(e.state.form, { username: 'alice', password: 'p' })
    await e.state.testConnection()
    assert.equal(e.state.testResult.value.success, false)
    assert.equal(e.state.testResult.value.message.includes('成功'), false)
    assert.equal(e.state.testResult.value.elapsedMs, 23)
  } finally { e.unmount() }
})
