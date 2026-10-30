import test from 'node:test'
import assert from 'node:assert/strict'
import * as vue from 'vue'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'

test('standalone viewer distinguishes identical paths by server in its title', async () => {
  const route = vue.reactive({ query: { token: 'viewer-token', path: '/var/log/app.log', host: '10.188.81.153' } })
  const document = { title: '' }
  const viewer = mountSfcSetup(new URL('../src/views/ssh/SshFileViewerStandalone.vue', import.meta.url), {
    modules: { 'vue-router': { useRoute: () => route } }, globals: { document }
  })
  try {
    assert.equal(document.title, '10.188.81.153 · app.log - 文件查看 - Doumi Shell')
    assert.equal(viewer.state.serverAddress.value, '10.188.81.153')
    route.query.host = '10.188.81.154:2222'
    await vue.nextTick()
    assert.equal(document.title, '10.188.81.154:2222 · app.log - 文件查看 - Doumi Shell')
    delete route.query.host
    await vue.nextTick()
    assert.equal(document.title, 'app.log - 文件查看 - Doumi Shell')
  } finally { viewer.unmount() }
})

test('opening a standalone viewer from its dialog carries the server and file identity', () => {
  let resolved, opened
  const dialog = mountSfcSetup(new URL('../src/components/ssh/SshLargeFileViewerDialog.vue', import.meta.url), {
    props: { accountId: 7, serverAddress: '10.188.81.153', sessionToken: 'viewer-token', dir: '/var/log', file: { name: 'app.log', size: 42 } },
    modules: { 'vue-router': { useRouter: () => ({ resolve: route => { resolved = route; return { href: '/viewer' } } }) } },
    globals: { window: { open: (...args) => { opened = args } } }
  })
  try {
    dialog.state.openStandalone('GBK')
    assert.deepEqual(resolved.query, { token: 'viewer-token', accountId: 7, host: '10.188.81.153', path: '/var/log/app.log', name: 'app.log', size: 42, enc: 'GBK' })
    assert.equal(opened[0], '/viewer')
  } finally { dialog.unmount() }
})
