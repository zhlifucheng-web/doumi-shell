import { test } from 'node:test'
import assert from 'node:assert/strict'
import { hasNewerVersion, requestLatestRelease } from '../src/utils/releaseCheck.js'

const reply = data => ({ ok: true, json: async () => ({ success: true, code: 200, data }) })
test('compares numeric versions and rejects unrecognised release formats', () => {
  assert.equal(hasNewerVersion('1.0.18', '1.0.17'), true)
  assert.equal(hasNewerVersion('v1.0.18', '1.0.18'), false)
  assert.equal(hasNewerVersion('1.0.9', '1.0.18'), false)
  assert.equal(hasNewerVersion('1.0.100', '1.0.99'), true)
  assert.throws(() => hasNewerVersion('1.0.18-beta', '1.0.17'))
})
test('uses the local backend and exact trusted release/download addresses', async () => {
  const result = await requestLatestRelease('1.0.17', { refresh: true, fetchImpl: async (url, options) => {
    assert.equal(url, '/api/ssh-console/releases/latest?refresh=true')
    assert.equal(options.cache, 'no-store')
    return reply({version:'1.0.18',url:'https://untrusted.invalid/'})
  } })
  assert.equal(result.newer, true)
  assert.equal(result.url, 'https://gitee.com/hahadoumi/web-shell/releases/tag/v1.0.18')
  assert.match(result.windowsUrl, /Doumi%20Shell-1.0.18.msi$/)
})
test('rate limits and server failures reject instead of returning up-to-date', async () => {
  await assert.rejects(requestLatestRelease('1.0.17', { fetchImpl: async () => reply({error:'Gitee HTTP 429，请稍后重试'}) }), /429/)
  await assert.rejects(requestLatestRelease('1.0.17', { fetchImpl: async () => ({ok:false,status:502}) }), /HTTP 502/)
  await assert.rejects(requestLatestRelease('1.0.17', { fetchImpl: async () => ({ok:true,json:async()=>({success:false,code:500,message:'服务错误'})}) }), /服务错误/)
})
test('network and malformed responses provide a manual-download fallback instruction', async () => {
  await assert.rejects(requestLatestRelease('1.0.17', { fetchImpl: async () => { throw new TypeError('Failed to fetch') } }), /Gitee 下载页面/)
  await assert.rejects(requestLatestRelease('1.0.17', { fetchImpl: async () => { throw new SyntaxError('bad json') } }), /运行中的程序和网页版本一致/)
  await assert.rejects(requestLatestRelease('1.0.17', { fetchImpl: async () => reply(null) }), /版本号格式无效/)
})
