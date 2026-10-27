import test from 'node:test'
import assert from 'node:assert/strict'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'
import * as vue from 'vue'

const filename = new URL('../src/components/ssh/SshFilePanel.vue', import.meta.url)

function mountPanel(props = {}, listDirectory, fileLocation = vue.ref(null), loadUsers) {
  const requests = []
  const events = []
  const pathEvents = []
  const warnings = []
  const errors = []
  const invalid = []
  const modules = {
    'element-plus': { ElMessage: { warning: message => warnings.push(message), error: message => errors.push(message) } },
    'vue-router': { useRouter: () => ({}) },
    '@/composables/useAiChanges': { useAiChanges: () => {} },
    '@/composables/useTransfers': { useTransfers: () => ({ transfers: vue.ref([]), assignTransferHost: () => {} }) },
    pinia: { storeToRefs: store => store },
    '@/stores/workspace': { useWorkspaceStore: () => ({ fileLocation }) },
    '@/api/sshConsole': {
      sftpList: async (token, path) => {
        requests.push({ token, path })
        if (listDirectory) return listDirectory(token, path)
        return { path: path || '/home/demo', items: [{ name: 'docs', dir: true }] }
      },
      sftpGetUsersAndGroups: loadUsers || (async () => ({ canViewOtherUsers: false, canChown: false, users: [], groups: [] }))
    }
  }
  const mounted = mountSfcSetup(filename, {
    modules, globals: { localStorage: { getItem: () => null } },
    props: { ...props, onPathChange: (path, tabId) => { events.push(path); pathEvents.push({ path, tabId }) }, onSessionInvalid: () => invalid.push(true) }
  })
  return { ...mounted, requests, events, pathEvents, warnings, errors, invalid }
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
    await flush()
    assert.equal(panel.state.loadedPath.value, '/home/demo/docs')
    assert.deepEqual(panel.requests[1], { token: 'test-session', path: '/home/demo/docs' })
    assert.deepEqual(panel.events, ['/home/demo', '/home/demo/docs'])
    panel.state.currentPath.value = ''
    await vue.nextTick()
    assert.deepEqual(panel.events, ['/home/demo', '/home/demo/docs'])
  } finally { panel.unmount() }
})

const flush = async () => { await new Promise(setImmediate); await vue.nextTick() }

test('expired background user lookup invalidates the current session without a popup', async () => {
  const panel = mountPanel({ sessionToken: 'expired' }, undefined, vue.ref(null), async () => { throw new Error('会话不存在或已过期') })
  try {
    await flush()
    assert.equal(panel.invalid.length, 1)
    assert.equal(panel.state.canChown.value, false)
    assert.deepEqual(panel.errors, [])
  } finally { panel.unmount() }
})

test('a late user lookup cannot replace permissions from a new session', async () => {
  let resolveOld
  const panel = mountPanel({ sessionToken: 'old' }, undefined, vue.ref(null), token => token === 'old'
    ? new Promise(resolve => { resolveOld = resolve })
    : Promise.resolve({ canViewOtherUsers: false, canChown: false, users: ['new-user'], groups: ['new-group'] }))
  try {
    panel.props.sessionToken = 'new'
    await flush()
    resolveOld({ canViewOtherUsers: true, canChown: true, users: ['old-user'], groups: ['old-group'] })
    await flush()
    assert.equal(panel.state.canChown.value, false)
    assert.deepEqual(panel.state.systemUsers.value, ['new-user'])
    assert.deepEqual(panel.state.systemGroups.value, ['new-group'])
  } finally { panel.unmount() }
})

test('a late expired user lookup cannot invalidate a new session', async () => {
  let rejectOld
  const panel = mountPanel({ sessionToken: 'old' }, undefined, vue.ref(null), token => token === 'old'
    ? new Promise((resolve, reject) => { rejectOld = reject })
    : Promise.resolve({ canViewOtherUsers: true, canChown: true, users: ['new-user'], groups: ['new-group'] }))
  try {
    panel.props.sessionToken = 'new'
    await flush()
    rejectOld(new Error('会话不存在或已过期'))
    await flush()
    assert.deepEqual(panel.invalid, [])
    assert.equal(panel.state.canChown.value, true)
  } finally { panel.unmount() }
})

test('a restored SFTP directory is used by the first request and identified by terminal tab', async () => {
  const panel = mountPanel({ sessionToken: 'test-session', accountId: 1, tabId: 'tab-a', savedPath: '/var/log', defaultCwd: '/home/demo' })
  try {
    await flush()
    assert.deepEqual(panel.requests, [{ token: 'test-session', path: '/var/log' }])
    assert.deepEqual(panel.pathEvents, [{ path: '/var/log', tabId: 'tab-a' }])
  } finally { panel.unmount() }
})

for (const message of ['No such file', 'Permission denied']) {
  test(`an unavailable restored directory (${message}) falls back once and saves only the successful path`, async () => {
    const panel = mountPanel({ sessionToken: 'test-session', tabId: 'tab-a', savedPath: '/removed', defaultCwd: '/home/demo' }, async (token, path) => {
      if (path === '/removed') throw new Error(message)
      return { path: '/home/demo', items: [] }
    })
    try {
      await flush()
      assert.deepEqual(panel.requests.map(r => r.path), ['/removed', '/home/demo'])
      assert.deepEqual(panel.pathEvents, [{ path: '/home/demo', tabId: 'tab-a' }])
      assert.equal(panel.warnings.length, 1)
      assert.equal(panel.errors.length, 0)
    } finally { panel.unmount() }
  })
}

test('an expired SSH session preserves the saved path for reconnect without directory fallback', async () => {
  const panel = mountPanel({ sessionToken: 'old-session', savedPath: '/var/log' }, async () => { throw new Error('会话不存在或已过期') })
  try {
    await flush()
    assert.deepEqual(panel.requests.map(r => r.path), ['/var/log'])
    assert.deepEqual(panel.events, [])
    assert.equal(panel.invalid.length, 1)
  } finally { panel.unmount() }
})

test('a response arriving after switching away cannot overwrite another tab directory', async () => {
  let resolveOld
  const oldPanel = mountPanel({ sessionToken: 'old', tabId: 'tab-a', savedPath: '/var/log' }, () => new Promise(resolve => { resolveOld = resolve }))
  oldPanel.unmount()
  const panel = mountPanel({ sessionToken: 'new', tabId: 'tab-b', savedPath: '/srv/app' })
  try {
    resolveOld({ path: '/var/log', items: [] })
    await flush()
    assert.deepEqual(oldPanel.events, [])
    assert.deepEqual(panel.pathEvents, [{ path: '/srv/app', tabId: 'tab-b' }])
  } finally { panel.unmount() }
})

test('failed manual navigation keeps the last successfully loaded directory', async () => {
  const panel = mountPanel({ sessionToken: 'session', tabId: 'tab-a' }, async (token, path) => {
    if (path) throw new Error('No such file')
    return { path: '/home/demo', items: [] }
  })
  try {
    await flush()
    panel.state.enterDir('missing')
    await flush()
    assert.deepEqual(panel.events, ['/home/demo'])
    assert.equal(panel.state.currentPath.value, '/home/demo')
    assert.equal(panel.errors.length, 1)
  } finally { panel.unmount() }
})

test('a saved directory survives waiting for SSH and reconnecting with a new session', async () => {
  const panel = mountPanel({ tabId: 'tab-a', accountId: 1, savedPath: '/var/log' })
  try {
    assert.deepEqual(panel.requests, [])
    panel.props.sessionToken = 'session-1'
    await flush()
    panel.props.sessionToken = ''
    await flush()
    panel.props.sessionToken = 'session-2'
    await flush()
    assert.deepEqual(panel.requests, [{ token: 'session-1', path: '/var/log' }, { token: 'session-2', path: '/var/log' }])
    assert.equal(panel.state.currentPath.value, '/var/log')
  } finally { panel.unmount() }
})

test('an inaccessible default directory ends fallback instead of retrying forever', async () => {
  const panel = mountPanel({ sessionToken: 'session', savedPath: '/removed', defaultCwd: '/restricted' }, async () => { throw new Error('Permission denied') })
  try {
    await flush()
    assert.deepEqual(panel.requests.map(r => r.path), ['/removed', '/restricted'])
    assert.equal(panel.warnings.length, 1)
    assert.equal(panel.errors.length, 1)
    assert.deepEqual(panel.events, [])
    assert.equal(panel.state.loading.value, false)
  } finally { panel.unmount() }
})


test('a file-location jump is consumed once so it cannot replace another tab saved path', async () => {
  const fileLocation = vue.ref({ accountId: 1, path: '/from-disk-panel' })
  const first = mountPanel({ accountId: 1, sessionToken: 'a', tabId: 'tab-a', savedPath: '/var/log' }, undefined, fileLocation)
  await flush()
  assert.equal(first.state.loadedPath.value, '/from-disk-panel')
  first.unmount()
  const second = mountPanel({ accountId: 1, sessionToken: 'b', tabId: 'tab-b', savedPath: '/srv/app' }, undefined, fileLocation)
  try {
    await flush()
    assert.deepEqual(first.requests.map(r => r.path), ['/from-disk-panel'])
    assert.deepEqual(second.requests.map(r => r.path), ['/srv/app'])
  } finally { second.unmount() }
})

test('switching same-account tabs in the existing panel restores their own paths and rejects an old response', async () => {
  let resolveOld
  const panel = mountPanel({ accountId: 1, tabId: 'tab-a', sessionToken: 'session', savedPath: '/var/log' }, (token, path) => {
    if (path === '/var/log') return new Promise(resolve => { resolveOld = resolve })
    return { path, items: [] }
  })
  try {
    panel.props.tabId = 'tab-b'
    panel.props.savedPath = '/srv/app'
    await flush()
    resolveOld({ path: '/var/log', items: [] })
    await flush()
    assert.deepEqual(panel.requests.map(r => r.path), ['/var/log', '/srv/app'])
    assert.equal(panel.state.loadedPath.value, '/srv/app')
    assert.deepEqual(panel.pathEvents, [{ path: '/srv/app', tabId: 'tab-b' }])
  } finally { panel.unmount() }
})
