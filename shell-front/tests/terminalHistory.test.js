import test from 'node:test'
import assert from 'node:assert/strict'
import { createHistoryRequest, findHistoryMatches, historyLineParts } from '../src/utils/terminalHistory.js'

test('history requests ignore stale responses after reload, close and reconnect', () => {
  const request = createHistoryRequest()
  const first = request.start()
  const second = request.start()
  assert.equal(request.accept(first), false)
  assert.equal(request.accept(second), true)
  assert.equal(request.accept(second), false)
  const third = request.start()
  request.cancel()
  assert.equal(request.accept(third), false)
})

test('literal case insensitive history search finds old output and repeated matches', () => {
  const lines = ['old 中文 [error]. ERROR', ...Array(2000).fill('recent output')]
  assert.deepEqual(findHistoryMatches(lines, 'error').matches, [
    { row: 0, start: 8, end: 13 }, { row: 0, start: 16, end: 21 }
  ])
  assert.equal(findHistoryMatches(lines, '[error].').matches.length, 1)
  assert.equal(findHistoryMatches(lines, '').matches.length, 0)
})

test('dense search results are bounded and highlighted as text rather than HTML', () => {
  const result = findHistoryMatches(['x x x x'], 'x', 3)
  assert.equal(result.matches.length, 3)
  assert.equal(result.limited, true)
  assert.deepEqual(historyLineParts('<script>x</script>', [{ start: 8, end: 9 }]), [
    { text: '<script>', match: false }, { text: 'x', match: true }, { text: '</script>', match: false }
  ])
})

test('case folding does not shift match offsets after Unicode characters', () => {
  assert.deepEqual(findHistoryMatches(['İ ERROR'], 'error').matches, [{ row: 0, start: 2, end: 7 }])
})
