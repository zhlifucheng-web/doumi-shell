import test from 'node:test'
import assert from 'node:assert/strict'
import { tmuxCompatibility } from '../src/utils/tmuxCompatibility.js'

test('plain SSH does not show tmux version or capabilities', () => {
  const state = tmuxCompatibility('SSH', '3.5a', 'server')
  assert.equal(state.tmux, false)
  assert.equal(state.label, 'SSH')
})

test('old, supported and double digit versions use numeric compatibility thresholds', () => {
  const old = tmuxCompatibility('SSH · tmux', '1.6', 'server')
  assert.equal(old.label, 'SSH · tmux 1.6')
  assert.equal(old.historySupported, false)
  assert.equal(old.features.find(f => f.key === 'history').status, '不支持')
  assert.equal(tmuxCompatibility('SSH · tmux', '1.8', 'server').historySupported, true)
  assert.equal(tmuxCompatibility('SSH · tmux', '3.10', 'server').historySupported, true)
  assert.equal(tmuxCompatibility('SSH · tmux', '3.5a', 'server').historySupported, true)
  assert.equal(tmuxCompatibility('SSH · tmux (只读共享)', '3.5a', 'server').label, 'SSH · tmux 3.5a (只读共享)')
})

test('unknown versions and executable-only versions do not claim server compatibility', () => {
  for (const version of ['', 'unknown', '#{version}', 'next-3.7']) {
    const state = tmuxCompatibility('SSH · tmux', version, 'unknown')
    assert.equal(state.historySupported, null)
    assert.equal(state.warning, true)
  }
  const executable = tmuxCompatibility('SSH · tmux', '3.5a', 'executable')
  assert.equal(executable.historySupported, null)
  assert.match(executable.note, /服务端版本未确认/)
  assert.equal(tmuxCompatibility('SSH · tmux', '1.6', 'executable').historySupported, false)
})

test('release candidates are not treated as a confirmed final minimum release', () => {
  assert.equal(tmuxCompatibility('SSH · tmux', '1.8-rc1', 'server').historySupported, false)
})
