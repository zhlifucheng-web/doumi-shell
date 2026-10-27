import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'

// Compile the complete production setup; only browser/service boundaries are replaced.
const filename = new URL('../src/components/ssh/SshFilePanel.vue', import.meta.url)
const { descriptor } = parse(readFileSync(filename, 'utf8'), { filename: filename.pathname })
const compiled = compileScript(descriptor, { id: 'sftp-setup-regression' }).content
const executable = compiled.replace(/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]/g, (_, bindings, source) => {
  const declaration = bindings.trim().startsWith('{')
    ? bindings.replace(/\bas\b/g, ':')
    : `{ default: ${bindings.trim()} }`
  return `const ${declaration} = modules[${JSON.stringify(source)}] || {};`
}).replace('export default', 'return')

function mountPanel(props = {}) {
  const requests = []
  const events = []
  const modules = {
    vue,
    'vue-router': { useRouter: () => ({}) },
    '@/composables/useAiChanges': { useAiChanges: () => {} },
    '@/composables/useTransfers': { useTransfers: () => ({ transfers: vue.ref([]), assignTransferHost: () => {} }) },
    pinia: { storeToRefs: store => store },
    '@/stores/workspace': { useWorkspaceStore: () => ({ fileLocation: vue.ref(null) }) },
    '@/api/sshConsole': {
      sftpList: async (token, path) => {
        requests.push({ token, path })
        return { path: path || '/home/demo', items: [{ name: 'docs', dir: true }] }
      },
      sftpGetUsersAndGroups: async () => ({ canViewOtherUsers: false, canChown: false, users: [], groups: [] })
    }
  }
  const component = new Function('modules', 'localStorage', executable)(modules, { getItem: () => null })
  let state
  const renderer = vue.createRenderer({
    createComment: () => ({}), insert: () => {}, remove: () => {}, parentNode: () => null, nextSibling: () => null
  })
  const app = renderer.createApp({
    ...component,
    setup(props, context) {
      state = component.setup(props, context)
      return () => null
    }
  }, { ...props, onPathChange: path => events.push(path) })
  app.config.errorHandler = error => { throw error }
  app.mount({})
  return { state, requests, events, unmount: () => app.unmount() }
}

test('SFTP panel initializes before a session exists and does not request a directory', () => {
  const panel = mountPanel()
  try {
    assert.equal(panel.state.currentPath.value, '')
    assert.deepEqual(panel.requests, [])
    assert.deepEqual(panel.events, [])
  } finally { panel.unmount() }
})

test('SFTP panel loads its initial directory and reports subsequent directory navigation', async () => {
  const panel = mountPanel({ sessionToken: 'test-session', accountId: 1, hostId: 2 })
  try {
    await Promise.resolve()
    await vue.nextTick()
    assert.equal(panel.state.loadedPath.value, '/home/demo')
    assert.deepEqual(panel.requests, [{ token: 'test-session', path: '' }])
    assert.deepEqual(panel.events, ['/home/demo'])
    panel.state.enterDir('docs')
    await vue.nextTick()
    assert.equal(panel.state.loadedPath.value, '/home/demo/docs')
    assert.deepEqual(panel.requests[1], { token: 'test-session', path: '/home/demo/docs' })
    assert.deepEqual(panel.events, ['/home/demo', '/home/demo/docs'])
    panel.state.currentPath.value = ''
    await vue.nextTick()
    assert.deepEqual(panel.events, ['/home/demo', '/home/demo/docs'])
  } finally { panel.unmount() }
})
