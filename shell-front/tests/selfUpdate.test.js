import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createUpdateClient, updatePhase } from '../src/utils/selfUpdate.js'
const reply = data => ({ ok: true, json: async () => ({ success: true, code: 200, data }) })
test('settings and installation send token headers and JSON only to the local update API', async () => {
  const calls = []
  const client = createUpdateClient({ fetchImpl: async (url, options) => { calls.push({ url, options }); return reply({ phase: 'IDLE' }) } })
  await client('settings', { method: 'PUT', token: 'secret', body: { proxy: 'https://gh-proxy.com/' } })
  assert.equal(calls[0].url, '/api/ssh-console/update/settings')
  assert.equal(calls[0].options.headers['X-Doumi-Update-Token'], 'secret')
  assert.deepEqual(JSON.parse(calls[0].options.body), { proxy: 'https://gh-proxy.com/' })
  assert.equal(calls[0].options.cache, 'no-store')
  await client('install', { method: 'POST', token: 'secret', body: { version: '1.0.63' } })
  assert.equal(calls[1].options.method, 'POST')
  await assert.rejects(client('https://evil.invalid'), /无效/)
})
test('HTTP and business errors preserve the server recovery instruction', async () => {
  await assert.rejects(createUpdateClient({ fetchImpl: async () => ({ ok: false, status: 403, json: async () => ({ message: '更新管理密钥不正确' }) }) })('install'), /更新管理密钥不正确/)
  await assert.rejects(createUpdateClient({ fetchImpl: async () => reply(null) })('status'), /无效/)
  await assert.rejects(createUpdateClient({ fetchImpl: async () => ({ ok: true, json: async () => ({ success: false, message: '仍有文件传输' }) }) })('status'), /仍有文件传输/)
})
test('rollback and success have distinct user-facing states', () => {
  assert.equal(updatePhase('SUCCEEDED').type, 'success')
  assert.equal(updatePhase('ROLLED_BACK').type, 'warning')
  assert.equal(updatePhase('ROLLBACK_FAILED').type, 'error')
  assert.equal(updatePhase('VERIFYING').label, '校验安装包')
})
