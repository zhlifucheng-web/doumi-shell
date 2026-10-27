import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

function loadApi(response) {
  const errors = []
  const client = { get: async () => response, interceptors: { request: { use() {} } } }
  const axios = { create: () => client }
  const source = readFileSync(new URL('../src/utils/apiHandler.js', import.meta.url), 'utf8')
    .replace(/^import .* from .*$/gm, '')
    .replace(/import\.meta\.env/g, '({})')
    .replace(/export \{[^}]+\}/g, '')
    .replace('export default', 'const defaultExport =')
    .replace(/export /g, '')
  const api = new Function('axios', 'ElMessage', `${source}; return { get, handleApiResponse }`)(axios, { error: message => errors.push(message) })
  const sshSource = readFileSync(new URL('../src/api/sshConsole.js', import.meta.url), 'utf8')
    .replace(/^import .* from .*$/gm, '').replace(/export /g, '')
  const ssh = new Function('get', `${sshSource}; return { sftpGetUsersAndGroups }`)(api.get)
  return { ...api, ...ssh, errors }
}

const expiredResponse = () => ({ data: { code: 400, success: false, message: '入参不能为空', externMessage: '会话不存在或已过期' } })

test('one business failure produces one global notification and still rejects', async () => {
  const api = loadApi(expiredResponse())
  await assert.rejects(api.get('/example'), /会话不存在或已过期/)
  assert.equal(api.errors.length, 1)
})

test('background SFTP user lookup rejects expired sessions without global notifications', async () => {
  const api = loadApi(expiredResponse())
  await assert.rejects(api.sftpGetUsersAndGroups('expired'), /会话不存在或已过期/)
  assert.deepEqual(api.errors, [])
})

test('background SFTP user lookup still returns successful permission data', async () => {
  const data = { canViewOtherUsers: true, canChown: true, users: ['root'], groups: ['root'] }
  const api = loadApi({ data: { code: 200, success: true, data } })
  assert.deepEqual(await api.sftpGetUsersAndGroups('current'), data)
  assert.deepEqual(api.errors, [])
})
