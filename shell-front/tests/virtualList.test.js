import test from 'node:test'
import assert from 'node:assert/strict'

function computeVirtualSlice({ totalCount, rowHeight, viewportHeight, scrollTop, buffer = 8 }) {
  if (!totalCount || totalCount <= 0) {
    return {
      startIndex: 0,
      endIndex: 0,
      topPadding: 0,
      bottomPadding: 0,
      totalHeight: 0,
      visibleCount: 0
    }
  }

  const totalHeight = totalCount * rowHeight
  const visibleCount = Math.ceil(viewportHeight / rowHeight)
  const startIndex = Math.max(0, Math.floor(scrollTop / rowHeight) - buffer)
  const endIndex = Math.min(totalCount, startIndex + visibleCount + 2 * buffer)
  const topPadding = startIndex * rowHeight
  const bottomPadding = Math.max(0, (totalCount - endIndex) * rowHeight)

  return {
    startIndex,
    endIndex,
    topPadding,
    bottomPadding,
    totalHeight,
    visibleCount
  }
}

test('virtual slice: handles empty items', () => {
  const result = computeVirtualSlice({
    totalCount: 0,
    rowHeight: 36,
    viewportHeight: 400,
    scrollTop: 0
  })

  assert.equal(result.startIndex, 0)
  assert.equal(result.endIndex, 0)
  assert.equal(result.topPadding, 0)
  assert.equal(result.bottomPadding, 0)
  assert.equal(result.totalHeight, 0)
})

test('virtual slice: small list renders all items with zero bottom padding', () => {
  const result = computeVirtualSlice({
    totalCount: 15,
    rowHeight: 36,
    viewportHeight: 500,
    scrollTop: 0,
    buffer: 8
  })

  assert.equal(result.startIndex, 0)
  assert.equal(result.endIndex, 15)
  assert.equal(result.topPadding, 0)
  assert.equal(result.bottomPadding, 0)
  assert.equal(result.totalHeight, 15 * 36)
})

test('virtual slice: large directory (5,000 files) renders only ~30 rows in DOM with exact total height', () => {
  const totalCount = 5000
  const rowHeight = 36
  const viewportHeight = 400 // ~12 visible rows
  const scrollTop = 36000 // Scrolled down to item 1,000

  const result = computeVirtualSlice({
    totalCount,
    rowHeight,
    viewportHeight,
    scrollTop,
    buffer: 8
  })

  // 36000 / 36 = 1000. With buffer of 8: startIndex = 992
  assert.equal(result.startIndex, 992)
  // Visible count = ceil(400/36) = 12. endIndex = 992 + 12 + 16 = 1020
  assert.equal(result.endIndex, 1020)
  assert.equal(result.endIndex - result.startIndex, 28) // Only 28 rows mounted in DOM!

  // Check pixel exactness:
  // topPadding + (rendered rows * 36) + bottomPadding must equal totalHeight (180,000px)
  const renderedHeight = (result.endIndex - result.startIndex) * rowHeight
  assert.equal(result.topPadding + renderedHeight + result.bottomPadding, totalCount * rowHeight)
  assert.equal(result.totalHeight, 5000 * 36)
})

test('virtual slice: scrolled near the very bottom clamps correctly without overflow', () => {
  const totalCount = 1000
  const rowHeight = 36
  const viewportHeight = 400
  const scrollTop = 36000 - 400 // At the bottom

  const result = computeVirtualSlice({
    totalCount,
    rowHeight,
    viewportHeight,
    scrollTop,
    buffer: 8
  })

  assert.equal(result.endIndex, 1000)
  assert.equal(result.bottomPadding, 0)
  const renderedHeight = (result.endIndex - result.startIndex) * rowHeight
  assert.equal(result.topPadding + renderedHeight + result.bottomPadding, totalCount * rowHeight)
})
