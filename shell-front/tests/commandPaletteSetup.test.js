import test from 'node:test'
import assert from 'node:assert/strict'
import { nextTick } from 'vue'
import { mountSfcSetup } from './helpers/mountSfcSetup.js'

function mountPalette(fileContext = null) {
  const events = [], opened = []
  const context = fileContext && { accountId: 7, tabId: 'tab-a', path: '/var/log', ...fileContext, openFile: file => { opened.push(file); return true } }
  const mounted = mountSfcSetup(new URL('../src/components/CommandPalette.vue', import.meta.url), {
    modules: { '@/api/sshConsole': { fetchSearchTargets: async () => [{ accountId: 9, hostId: 1, username: 'root', hostName: 'app.log', hostAddress: '10.0.0.1' }] } },
    props: { modelValue: true, fileContext: context, 'onUpdate:modelValue': value => events.push(['close', value]), onSelectTarget: target => events.push(['target', target]), onAction: action => events.push(['action', action]), onAskAi: prompt => events.push(['ai', prompt]) }
  })
  return { ...mounted, opened, events }
}
const flush = async () => { await new Promise(setImmediate); await nextTick() }

test('the existing Ctrl+K palette searches current directory files before hosts and opens via Enter', async () => {
  const palette = mountPalette({ files: [{ name: 'app.log', size: 90 }, { name: 'error.log', size: 80 }] })
  try {
    await flush(); palette.state.query.value = 'APP.log'; await flush()
    const results = palette.state.filteredItems.value
    assert.equal(results[0].isHeader, true)
    assert.match(results[0].label, /当前目录文件/)
    assert.equal(results[1].label, 'app.log')
    assert.equal(palette.state.activeIndex.value, 1)
    palette.state.selectActive()
    assert.deepEqual(palette.opened.map(f => f.name), ['app.log'])
    assert.deepEqual(palette.events, [['close', false]])
  } finally { palette.unmount() }
})

test('files are limited to the current scope while command and AI prefixes retain their behavior', async () => {
  const palette = mountPalette({ files: [{ name: 'clear.log' }, { name: 'error.LOG' }, { name: 'my log.txt' }] })
  try {
    await flush(); palette.state.query.value = 'my log'; await flush()
    assert.deepEqual(palette.state.filteredItems.value.filter(i => i.type === 'file').map(i => i.label), ['my log.txt'])
    palette.state.query.value = '>clear'; await flush()
    assert.equal(palette.state.filteredItems.value.some(i => i.type === 'file'), false)
    palette.state.selectActive()
    assert.deepEqual(palette.events.at(-1), ['action', 'clear'])
    palette.state.query.value = '? help'; await flush(); palette.state.selectActive()
    assert.deepEqual(palette.events.at(-1), ['ai', 'help'])
  } finally { palette.unmount() }
})

test('switching the active directory removes obsolete file results and refuses a stale selection', async () => {
  const palette = mountPalette({ files: [{ name: 'old.log' }] })
  try {
    await flush(); palette.state.query.value = 'old.log'; await flush()
    const old = palette.state.filteredItems.value.find(i => i.type === 'file')
    assert.ok(old)
    palette.props.fileContext = { accountId: 8, tabId: 'tab-b', path: '/other', files: [{ name: 'new.log' }], openFile: () => assert.fail('stale target must not open') }
    await flush(); palette.state.execute(old)
    assert.deepEqual(palette.opened, []); assert.deepEqual(palette.events, [])
    assert.equal(palette.state.filteredItems.value.some(i => i.type === 'file'), false)
    palette.state.query.value = 'new.log'; await flush()
    assert.equal(palette.state.filteredItems.value.find(i => i.type === 'file').label, 'new.log')
    palette.props.fileContext = null; await flush()
    assert.equal(palette.state.filteredItems.value.some(i => i.type === 'file'), false)
  } finally { palette.unmount() }
})

test('large directories cap rendered results while searching every filename', async () => {
  const palette = mountPalette({ files: Array.from({ length: 5000 }, (_, n) => ({ name: `file-${n}.log` })) })
  try {
    await flush()
    assert.ok(palette.state.filteredItems.value.filter(i => i.type === 'file').length <= 50)
    palette.state.query.value = 'file-4999.log'; await flush()
    assert.equal(palette.state.filteredItems.value.find(i => i.type === 'file')?.label, 'file-4999.log')
  } finally { palette.unmount() }
})
