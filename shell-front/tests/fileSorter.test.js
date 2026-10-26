import test from 'node:test'
import assert from 'node:assert/strict'
import { sortFileList, normalizeOrder } from '../src/utils/fileSorter.js'

test('normalizeOrder converts variations to ascending / descending', () => {
  assert.equal(normalizeOrder('asc'), 'ascending')
  assert.equal(normalizeOrder('ascending'), 'ascending')
  assert.equal(normalizeOrder('ASC'), 'ascending')
  assert.equal(normalizeOrder('desc'), 'descending')
  assert.equal(normalizeOrder('descending'), 'descending')
  assert.equal(normalizeOrder('DESC'), 'descending')
  assert.equal(normalizeOrder(null), 'ascending')
  assert.equal(normalizeOrder(undefined), 'ascending')
})

test('directories always stay at top in both ascending and descending sorts', () => {
  const items = [
    { name: 'file-b.txt', dir: false, mtime: 100 },
    { name: 'dir-z', dir: true, mtime: 200 },
    { name: 'file-a.txt', dir: false, mtime: 300 },
    { name: 'dir-a', dir: true, mtime: 50 }
  ]

  // Name asc
  const nameAsc = sortFileList(items, { prop: 'name', order: 'ascending' })
  assert.deepEqual(nameAsc.map(i => i.name), ['dir-a', 'dir-z', 'file-a.txt', 'file-b.txt'])

  // Name desc
  const nameDesc = sortFileList(items, { prop: 'name', order: 'descending' })
  assert.deepEqual(nameDesc.map(i => i.name), ['dir-z', 'dir-a', 'file-b.txt', 'file-a.txt'])

  // Mtime asc (oldest first)
  const mtimeAsc = sortFileList(items, { prop: 'mtime', order: 'ascending' })
  assert.deepEqual(mtimeAsc.map(i => i.name), ['dir-a', 'dir-z', 'file-b.txt', 'file-a.txt'])

  // Mtime desc (newest first)
  const mtimeDesc = sortFileList(items, { prop: 'mtime', order: 'descending' })
  assert.deepEqual(mtimeDesc.map(i => i.name), ['dir-z', 'dir-a', 'file-a.txt', 'file-b.txt'])
})

test('natural alphanumeric and Chinese collation for filenames', () => {
  const items = [
    { name: 'log10.txt', dir: false },
    { name: 'log2.txt', dir: false },
    { name: 'log1.txt', dir: false },
    { name: 'app.js', dir: false },
    { name: '北京.txt', dir: false },
    { name: '上海.txt', dir: false }
  ]

  const sorted = sortFileList(items, { prop: 'name', order: 'ascending' })
  const names = sorted.map(i => i.name)
  // Natural sorting: log1 < log2 < log10
  assert.ok(names.indexOf('log1.txt') < names.indexOf('log2.txt'))
  assert.ok(names.indexOf('log2.txt') < names.indexOf('log10.txt'))
  assert.ok(names.indexOf('app.js') < names.indexOf('log1.txt'))
})

test('sorting by modification time (mtime)', () => {
  const items = [
    { name: 'f1', dir: false, mtime: 1700000010 },
    { name: 'f2', dir: false, mtime: 1700000050 },
    { name: 'f3', dir: false, mtime: 1700000020 }
  ]

  const desc = sortFileList(items, { prop: 'mtime', order: 'descending' })
  assert.deepEqual(desc.map(i => i.name), ['f2', 'f3', 'f1'])

  const asc = sortFileList(items, { prop: 'mtime', order: 'ascending' })
  assert.deepEqual(asc.map(i => i.name), ['f1', 'f3', 'f2'])
})

test('sorting by creation time (createTime) with fallback to atime and mtime', () => {
  const items = [
    { name: 'f_created', dir: false, createTime: 1700000100 },
    { name: 'f_atime_fallback', dir: false, atime: 1700000200 },
    { name: 'f_mtime_fallback', dir: false, mtime: 1700000050 }
  ]

  const desc = sortFileList(items, { prop: 'createTime', order: 'descending' })
  assert.deepEqual(desc.map(i => i.name), ['f_atime_fallback', 'f_created', 'f_mtime_fallback'])

  const asc = sortFileList(items, { prop: 'createTime', order: 'ascending' })
  assert.deepEqual(asc.map(i => i.name), ['f_mtime_fallback', 'f_created', 'f_atime_fallback'])
})

test('sorting by file size', () => {
  const items = [
    { name: 'small', dir: false, size: 500 },
    { name: 'large', dir: false, size: 500000 },
    { name: 'medium', dir: false, size: 25000 }
  ]

  const desc = sortFileList(items, { prop: 'size', order: 'descending' })
  assert.deepEqual(desc.map(i => i.name), ['large', 'medium', 'small'])

  const asc = sortFileList(items, { prop: 'size', order: 'ascending' })
  assert.deepEqual(asc.map(i => i.name), ['small', 'medium', 'large'])
})

test('tie breaker falls back to natural name sort and preserves immutability', () => {
  const items = [
    { name: 'b', dir: false, mtime: 100 },
    { name: 'a', dir: false, mtime: 100 },
    { name: 'c', dir: false, mtime: 100 }
  ]

  const originalCopy = [...items]
  const sorted = sortFileList(items, { prop: 'mtime', order: 'descending' })
  // Names tie-broken by name ascending
  assert.deepEqual(sorted.map(i => i.name), ['a', 'b', 'c'])
  // Original array not mutated
  assert.deepEqual(items, originalCopy)
})
