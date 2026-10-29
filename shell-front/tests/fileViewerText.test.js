import test from 'node:test'
import assert from 'node:assert/strict'
import { highlightText, searchMatches, interceptFind, nextMatch } from '../src/utils/fileViewerText.js'

test('literal search highlights raw text without matching or corrupting HTML entities', () => {
  assert.equal(highlightText('<tag> & amp', 'amp', 0), '&lt;tag&gt; &amp; <mark data-search-index="0" class="search-match is-current">amp</mark>')
  assert.equal(highlightText('<tag>', '<', 0), '<mark data-search-index="0" class="search-match is-current">&lt;</mark>tag&gt;')
  assert.equal(highlightText('<script>x</script>', '', -1), '&lt;script&gt;x&lt;/script&gt;')
})

test('search treats regex metacharacters literally and navigation wraps', () => {
  assert.deepEqual(searchMatches('A.b a.B ab', 'a.b'), [{ index: 0, length: 3 }, { index: 4, length: 3 }])
  assert.equal(nextMatch(-1, 2, 1), 0)
  assert.equal(nextMatch(1, 2, 1), 0)
  assert.equal(nextMatch(0, 2, -1), 1)
  assert.equal(nextMatch(0, 0, 1), -1)
})

test('Ctrl/Cmd+F suppresses browser find and propagation before focusing viewer search', () => {
  for (const modifier of ['ctrlKey', 'metaKey']) {
    const calls = []
    assert.equal(interceptFind({ key: 'F', [modifier]: true, preventDefault: () => calls.push('prevent'), stopPropagation: () => calls.push('stop') }, () => calls.push('focus')), true)
    assert.deepEqual(calls, ['prevent', 'stop', 'focus'])
  }
  assert.equal(interceptFind({ key: 'f' }, () => assert.fail('must not focus')), false)
})

test('incremental blocks retain unchanged nodes and highlight matches crossing batch boundaries', async () => {
  const utils = await import('../src/utils/fileViewerText.js')
  assert.equal(typeof utils.createViewerBlockRenderer, 'function')
  const render = utils.createViewerBlockRenderer()
  const first = { offset: 0, text: 'one\n' }, second = { offset: 4, text: 'hel' }
  const initial = render([first, second], '', -1)
  const appended = render([first, second, { offset: 7, text: 'lo<&\n' }], '', -1)
  assert.equal(appended[0], initial[0]); assert.equal(appended[1], initial[1])
  assert.equal(appended[2].html, 'lo&lt;&amp;\n')
  const found = render([first, second, { offset: 7, text: 'lo<&\n' }], 'hello', 0)
  assert.match(found[1].html, /is-current[^>]*>hel<\/mark>/)
  assert.match(found[2].html, /is-current[^>]*>lo<\/mark>&lt;&amp;/)
  assert.equal(found[1].html.includes('data-search-index="0"'), true)
  assert.equal(found[2].html.includes('data-search-index="0"'), true)
})

test('incremental boundary search agrees with whole-text literal search across partitions', async () => {
  const { createViewerBlockRenderer } = await import('../src/utils/fileViewerText.js')
  for (const [text, keyword] of [['aaaaaa', 'aa'], ['abababa', 'aba'], ['HELLO hello', 'hello'], ['<&long match!&>', 'long match!'], ['abcdef', 'abcdef']]) {
    for (let width = 1; width <= text.length; width++) {
      const items = [];for (let n = 0; n < text.length; n += width) items.push({ offset: n, text: text.slice(n, n + width) })
      const render = createViewerBlockRenderer(), blocks = render(items, keyword, 0)
      assert.equal(blocks.matchCount, searchMatches(text, keyword).length, `${text}/${keyword}/${width}`)
      assert.equal(render([...items, { offset: text.length, text: '\n' }], keyword, 0)[0].html, blocks[0].html, 'appended newline preserves existing highlights')
    }
  }
})

test('long filtered line marks omitted content and invalidates only its metadata-dependent block', async () => {
  const { createViewerBlockRenderer } = await import('../src/utils/fileViewerText.js')
  const render = createViewerBlockRenderer()
  const first = { lineNumber: 1, text: 'hit visible', matched: true, truncated: false }
  const second = { lineNumber: 2, text: 'other', matched: false }
  const initial = render([first, second], 'hit', 0, true)
  const changed = render([{ ...first, truncated: true }, second], 'hit', 0, true)
  assert.match(changed[0].html, /超长行，部分内容省略/)
  assert.match(changed[0].html, /data-line="1"/)
  assert.match(changed[0].html, />hit<\/mark>/)
  assert.doesNotMatch(initial[0].html, /部分内容省略/)
  assert.equal(changed[1], initial[1])
  assert.equal(changed.matchCount, 1)
})

test('local rows visibly label fragment line numbers and never imply absolute source lines', async () => {
  const { createViewerBlockRenderer } = await import('../src/utils/fileViewerText.js')
  const render = createViewerBlockRenderer(), row = { lineNumber: 3, text: 'hit', matched: true }
  const global = render([row], '', -1, true)
  const local = render([{ ...row, fragment: true }], '', -1, true)
  assert.match(local[0].html, /片段行号 3/)
  assert.match(local[0].html, /data-line="片段 3"/)
  assert.doesNotMatch(global[0].html, /片段行号/)
})

test('case-sensitive filter highlighting follows its literal match option without changing default find', async () => {
  const { createViewerBlockRenderer } = await import('../src/utils/fileViewerText.js')
  const render = createViewerBlockRenderer(), rows = [{ lineNumber: 1, text: 'Hit hit', matched: true }]
  assert.equal(render(rows, 'hit', -1, true, true).matchCount, 1)
  assert.equal(render(rows, 'hit', -1, true, false).matchCount, 2)
})

test('local Unicode-insensitive matches also receive a visible highlight', async () => {
  const { createViewerBlockRenderer } = await import('../src/utils/fileViewerText.js')
  const render = createViewerBlockRenderer()
  const rows = [{ lineNumber: 1, text: '𐐨猫', matched: true, fragment: true }]
  assert.equal(render(rows, '𐐀猫', -1, true, false).matchCount, 1)
})
