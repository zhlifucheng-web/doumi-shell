import test from 'node:test'
import assert from 'node:assert/strict'
import { useViewerLocalFilter } from '../src/composables/useViewerLocalFilter.js'

const block = (key, text, offset = 0) => Object.freeze({ renderKey: key, text, offset, length: Buffer.byteLength(text) })
const texts = filter => filter.rows.value.map(row => row.text)
function stream(filter) {
  let offset = 0
  const blocks = []
  return text => {
    blocks.push(block(`b${offset}`, text, offset)); offset += Buffer.byteLength(text)
    if (blocks.length > 16) blocks.shift()
    filter.sync(blocks.slice())
    return blocks.slice()
  }
}

test('local filtering joins unfinished lines, upserts EOF and deduplicates overlapping context', () => {
  const filter = useViewerLocalFilter(), append = stream(filter)
  filter.configure({ keyword: 'error', caseSensitive: false, contextLines: 2 })
  append('before\nER'); assert.deepEqual(texts(filter), [])
  append('ROR one\nnear\nerror two\nafter')
  assert.deepEqual(texts(filter), ['before', 'ERROR one', 'near', 'error two', 'after'])
  assert.deepEqual(filter.rows.value.map(row => row.matched), [false, true, false, true, false])
  const key = filter.rows.value.at(-1).renderKey
  append(' more\nlast\n')
  assert.deepEqual(texts(filter), ['before', 'ERROR one', 'near', 'error two', 'after more', 'last'])
  assert.equal(filter.rows.value[4].renderKey, key)
  assert.equal(new Set(filter.rows.value.map(row => row.renderKey)).size, 6)
  assert.ok(filter.rows.value.every(row => row.fragment && row.lineNumber > 0))
})

test('changed options rebuild from raw blocks, including Unicode and case-sensitive matches', () => {
  const filter = useViewerLocalFilter()
  filter.sync([block('a', '猫咪\nERROR\nerror\nother\n')])
  filter.configure({ keyword: '猫咪', caseSensitive: true, contextLines: 0 })
  assert.deepEqual(texts(filter), ['猫咪'])
  filter.configure({ keyword: 'error', caseSensitive: true, contextLines: 0 })
  assert.deepEqual(texts(filter), ['error'])
  filter.configure({ keyword: 'error', caseSensitive: false, contextLines: 0 })
  assert.deepEqual(texts(filter), ['ERROR', 'error'])
  filter.configure({ keyword: 'other', caseSensitive: false, contextLines: 0 })
  assert.deepEqual(texts(filter), ['other'])
})

test('sliding a sixteen-block cache drops evicted matches without rescanning retained text', () => {
  const filter = useViewerLocalFilter(), append = stream(filter)
  filter.configure({ keyword: 'needle', contextLines: 0 })
  append('needle' + 'x'.repeat(32768))
  for (let n = 1; n < 16; n++) append('x'.repeat(32768))
  assert.equal(filter.rows.value.length, 1)
  const before = filter.stats.value.processedChars
  append('x'.repeat(32768))
  assert.deepEqual(texts(filter), [])
  assert.ok(filter.stats.value.processedChars - before < 34000)
  for (let n = 0; n < 20; n++) append('x'.repeat(32768))
  assert.deepEqual(texts(filter), [])
  append('needle\n')
  assert.equal(filter.rows.value.length, 1)
  assert.ok(filter.rows.value[0].text.includes('needle'))
  assert.ok(filter.stats.value.retainedBlocks <= 16)
  assert.ok(filter.stats.value.retainedChars <= 16 * 32774)
})

test('eviction repairs valid bridge matches when an earlier bridge used an evicted prefix', () => {
  const filter = useViewerLocalFilter(), append = stream(filter)
  filter.configure({ keyword: 'aaaa', contextLines: 0 })
  append('aaa'); append('aa'); append('aa')
  for (let n = 3; n < 16; n++) append('x')
  append('x')
  assert.equal(filter.rows.value.length, 1)
  assert.ok(filter.rows.value[0].text.startsWith('aaaa'))
  append('x')
  assert.deepEqual(texts(filter), [])
})

test('late and cross-block keywords in arbitrarily long lines get bounded honest previews', () => {
  const filter = useViewerLocalFilter(), append = stream(filter)
  filter.configure({ keyword: '猫needle咪', contextLines: 0 })
  append('x'.repeat(200000) + '猫nee')
  append('dle咪' + 'y'.repeat(100000) + '\nnone\n')
  assert.equal(filter.rows.value.length, 1)
  assert.equal(filter.rows.value[0].truncated, true)
  assert.ok(filter.rows.value[0].text.length <= 65536)
  assert.equal(filter.rows.value[0].text.indexOf('猫needle咪'), 128)
  filter.configure({ keyword: 'found', contextLines: 0 })
  append('z'.repeat(200000) + '\nfound\n')
  assert.deepEqual(texts(filter), ['found'])
})

test('prepend, navigation replacement and metadata changes rebuild the current cache', () => {
  const filter = useViewerLocalFilter()
  filter.configure({ keyword: 'hit', contextLines: 0 })
  const a = block('a', 'hit A\n', 10)
  filter.sync([a]); const key = filter.rows.value[0].renderKey
  filter.sync([block('pre', 'hit pre\n', 0), a])
  assert.deepEqual(texts(filter), ['hit pre', 'hit A'])
  filter.sync([block('a', 'hit changed\n', 10)])
  assert.deepEqual(texts(filter), ['hit changed'])
  assert.notEqual(filter.rows.value[0].renderKey, key)
  filter.sync([block('else', 'miss\n', 900)])
  assert.deepEqual(texts(filter), [])
  filter.clear(); assert.deepEqual(texts(filter), []); assert.equal(filter.stats.value.retainedChars, 0)
})

test('result row and character limits are explicit and recover after source replacement', () => {
  const filter = useViewerLocalFilter()
  filter.configure({ keyword: 'x', contextLines: 0 })
  filter.sync([block('rows', 'x\n'.repeat(10002))])
  assert.equal(filter.rows.value.length, 10000); assert.equal(filter.limited.value, true)
  filter.sync(Array.from({ length: 16 }, (_, n) => block(`wide${n}`, ('x' + 'a'.repeat(65535) + '\n').repeat(5), n * 327685)))
  assert.ok(filter.rows.value.reduce((sum, row) => sum + row.text.length, 0) <= 4 * 1024 * 1024)
  assert.equal(filter.limited.value, true)
  filter.sync([block('fresh', 'x\n')])
  assert.deepEqual(texts(filter), ['x']); assert.equal(filter.limited.value, false)
})

test('synthetic reset separators divide matching and context and appear only between results', () => {
  const filter = useViewerLocalFilter()
  const marker = Object.freeze({ renderKey: 'reset:1', separator: true, text: '\n=== reset ===\n' })
  filter.configure({ keyword: 'error', contextLines: 2 })
  filter.sync([block('a', 'error old\nleft'), marker, block('b', 'right\nerror new')])
  assert.deepEqual(texts(filter), ['error old', 'left', '\n=== reset ===\n', 'right', 'error new'])
  assert.equal(filter.rows.value[2].separator, true)
  assert.deepEqual(filter.rows.value.filter(row => !row.separator).map(row => row.lineNumber), [1, 2, 3, 4])
  filter.configure({ keyword: 'old', contextLines: 2 })
  assert.deepEqual(texts(filter), ['error old', 'left'])
  filter.configure({ keyword: 'leftright', contextLines: 0 })
  assert.deepEqual(texts(filter), [])
})

test('a context EOF row can become matched without changing its fragment identity', () => {
  const filter = useViewerLocalFilter(), append = stream(filter)
  filter.configure({ keyword: 'error', contextLines: 2 })
  append('error\npartial ')
  const row = filter.rows.value[1]
  assert.equal(row.matched, false)
  append('error')
  assert.equal(filter.rows.value[1].renderKey, row.renderKey)
  assert.equal(filter.rows.value[1].matched, true)
  assert.equal(filter.rows.value[1].text, 'partial error')
})

test('Unicode case folding and surrogate-pair keywords work across raw block boundaries', () => {
  const filter = useViewerLocalFilter(), append = stream(filter)
  filter.configure({ keyword: '𐐀猫', caseSensitive: false, contextLines: 0 })
  append('\ud801'); append('\udc28猫\n')
  assert.deepEqual(texts(filter), ['𐐨猫'])
})

test('dense nonmatching lines retain bounded summaries and still find a late match incrementally', () => {
  const filter = useViewerLocalFilter(), append = stream(filter)
  filter.configure({ keyword: 'needle', contextLines: 2 })
  for (let n = 0; n < 16; n++) append('a\n'.repeat(131072))
  assert.deepEqual(texts(filter), [])
  assert.ok(filter.stats.value.retainedLineSummaries <= 3)
  const before = filter.stats.value.processedChars
  append('before\nneedle\nafter\n')
  assert.deepEqual(texts(filter), ['a', 'before', 'needle', 'after'])
  assert.ok(filter.stats.value.processedChars - before < 100)
  assert.ok(filter.stats.value.retainedLineSummaries <= 8)
})

test('dense matching blocks keep bounded candidates and refill results after atomic eviction', () => {
  const filter = useViewerLocalFilter(), append = stream(filter)
  filter.configure({ keyword: 'x', contextLines: 0 })
  for (let n = 0; n < 16; n++) append('x\n'.repeat(20000))
  assert.equal(filter.rows.value.length, 10000)
  assert.equal(filter.limited.value, true)
  assert.ok(filter.stats.value.retainedLineSummaries <= 16 * 10016 + 3)
  const before = filter.stats.value.processedChars
  for (let n = 0; n < 15; n++) append('none\n')
  assert.equal(filter.rows.value.length, 10000)
  assert.equal(filter.rows.value[0].lineNumber, 300001)
  assert.equal(filter.limited.value, true)
  assert.ok(filter.stats.value.processedChars - before < 1000)
  append('final x\n')
  assert.deepEqual(texts(filter), ['final x'])
  assert.equal(filter.limited.value, false)
})

test('trailing CR is excluded at a snapshot but can become an internal matching character', () => {
  const filter = useViewerLocalFilter(), append = stream(filter)
  filter.configure({ keyword: '\r', contextLines: 0 })
  append('plain\r')
  assert.deepEqual(texts(filter), [])
  append('inside')
  assert.deepEqual(texts(filter), ['plain\rinside'])
  append('\r\nother\r\n')
  assert.deepEqual(texts(filter), ['plain\rinside'])
})

test('long-line previews do not cut valid surrogate pairs at either boundary', () => {
  const filter = useViewerLocalFilter()
  filter.configure({ keyword: 'needle', contextLines: 0 })
  filter.sync([block('emoji', 'x'.repeat(70000) + '😀' + 'a'.repeat(127) + 'needle' + '😀'.repeat(40000))])
  const preview = filter.rows.value[0].text
  assert.ok(preview.length <= 65536)
  assert.equal(preview.isWellFormed(), true)
  assert.ok(preview.includes('needle'))
})
