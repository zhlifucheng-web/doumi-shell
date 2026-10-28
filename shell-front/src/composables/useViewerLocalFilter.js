import { shallowRef } from 'vue'

const MAX_BLOCKS = 16, MAX_ROWS = 10000, MAX_CHARS = 4 * 1024 * 1024, PREVIEW_CHARS = 65536
// Whole-block eviction can invalidate only its successor's first fragment line.
// A small reserve keeps the first MAX_ROWS eligible rows available after repair.
const CANDIDATES_PER_BLOCK = MAX_ROWS + 6
const sameBlock = (a, b) => a && b && a.renderKey === b.renderKey && a.offset === b.offset && a.length === b.length && a.text === b.text && Boolean(a.separator) === Boolean(b.separator)
const highSurrogate = value => value >= 0xd800 && value <= 0xdbff
const lowSurrogate = value => value >= 0xdc00 && value <= 0xdfff

// This projection owns no reader, transport or Tail lifecycle. Its input is the
// viewer's immutable, bounded raw cache, never an earlier filtered projection.
export function useViewerLocalFilter() {
  const rows = shallowRef([]), limited = shallowRef(false)
  const stats = shallowRef({ processedChars: 0, rebuilds: 0, retainedBlocks: 0, retainedChars: 0, retainedLineSummaries: 0 })
  let blocks = [], lines = [], recent = [], pool = [], open = null, counts = new Map()
  let position = 0, ordinal = 0, order = 0, generation = 0, segment = 0, afterUntil = 0
  let options = { keyword: '', caseSensitive: false, contextLines: 0 }, matcher = null
  let processedChars = 0, rebuilds = 0

  function find(text, start) {
    processedChars += text.length
    if (!matcher) return null
    const found = matcher.exec(text)
    return found ? start + found.index : null
  }
  function slice(line, start, end) {
    let text = ''
    for (const part of line.parts) {
      if (part.end <= start) continue
      if (part.start >= end) break
      text += part.block.text.slice(part.from + Math.max(0, start - part.start), part.from + Math.min(part.end - part.start, end - part.start))
    }
    return text
  }
  function effectiveEnd(line) {
    return line.end > line.start && slice(line, line.end - 1, line.end) === '\r' ? line.end - 1 : line.end
  }
  function firstMatch(line) {
    if (line.separator) return null
    const end = effectiveEnd(line), length = options.keyword.length
    let first = line.repair !== null && line.repair >= line.start && line.repair + length <= end ? line.repair : null
    for (const part of line.parts) {
      const own = part.own, bridge = part.bridge
      if (own !== null && own >= line.start && own + length <= end && (first === null || own < first)) first = own
      if (bridge !== null && bridge >= line.start && bridge + length <= end && (first === null || bridge < first)) first = bridge
    }
    return first
  }
  function keep(line) {
    if (line.kept) return
    const owner = line.parts[0].block, count = counts.get(owner) || 0
    // Only one physical line can cross each adjacent block boundary. Keep these
    // separately so losing its old prefix never hides the next eligible row.
    if (!line.separator && line.parts.length === 1 && count >= CANDIDATES_PER_BLOCK) return
    if (!line.separator && line.parts.length === 1) counts.set(owner, count + 1)
    line.kept = true
    if (!line.key) line.key = `local:${generation}:${line.number}`
    let lo = 0, hi = lines.length
    while (lo < hi) { const mid = (lo + hi) >>> 1; if (lines[mid].order < line.order) lo = mid + 1; else hi = mid }
    lines.splice(lo, 0, line)
  }
  function consider(line) {
    if (firstMatch(line) !== null && options.keyword) {
      for (const previous of recent) if (previous.segment === line.segment && line.number - previous.number <= options.contextLines) keep(previous)
      keep(line)
      afterUntil = Math.max(afterUntil, line.number + options.contextLines)
    } else if (line.number <= afterUntil) keep(line)
  }
  function recycle(line) {
    if (line.kept) return
    line.row = null
    line.sparePart = line.parts[0]
    if (line.sparePart) line.sparePart.block = null
    line.parts.length = 0
    if (pool.length < 3) pool.push(line)
  }
  function newLine() {
    const line = pool.pop() || { parts: [] }
    line.number = ++ordinal; line.key = null; line.order = ++order; line.segment = segment
    line.start = position; line.end = position; line.version = 0; line.repair = null; line.row = null; line.kept = false
    return line
  }
  function append(block) {
    if (block.separator) {
      open = null; recent = []; afterUntil = 0; ++segment
      keep({ separator: true, key: `local:${generation}:separator:${block.renderKey}`, parts: [{ block, from: 0, start: position, end: position + block.text.length }], start: position, end: position + block.text.length, order: ++order })
      position += block.text.length
      return
    }
    let begin = 0
    while (begin < block.text.length) {
      const newline = block.text.indexOf('\n', begin), complete = newline !== -1
      const end = complete ? newline : block.text.length, text = block.text.slice(begin, end)
      if (!open) open = newLine()
      const overlap = Math.max(0, options.keyword.length - 1)
      const carry = overlap ? slice(open, Math.max(open.start, position - overlap), position) : ''
      const own = find(text, position)
      const bridge = carry ? find(carry + text.slice(0, overlap), position - carry.length) : null
      const part = open.sparePart || {}
      open.sparePart = null
      part.block = block; part.from = begin; part.start = position; part.end = position + text.length; part.own = own; part.bridge = bridge
      open.parts.push(part)
      position += text.length; open.end = position; open.version++
      consider(open)
      if (complete) {
        if (options.contextLines) { recent.push(open); if (recent.length > options.contextLines) recycle(recent.shift()) }
        else recycle(open)
        ++position; open = null
      }
      begin = complete ? end + 1 : end
    }
  }
  function evict(keepBlocks) {
    pool = []
    const retained = new Set(keepBlocks)
    const candidates = new Set([...lines, ...recent, ...(open ? [open] : [])])
    for (const line of candidates) {
      const parts = line.parts.filter(part => retained.has(part.block))
      if (!parts.length) { line.kept = false; line.parts = []; line.row = null; continue }
      if (parts.length !== line.parts.length) {
        line.parts = parts; line.start = parts[0].start; line.version++
        line.repair = find(slice(line, line.start, Math.min(line.end, line.start + options.keyword.length * 2)), line.start)
      }
    }
    lines = lines.filter(line => line.parts.length)
    recent = recent.filter(line => line.parts.length)
    if (open && !open.parts.length) open = null
    for (const block of counts.keys()) if (!retained.has(block)) counts.delete(block)
  }
  function publish() {
    const matches = lines.map(firstMatch), selected = new Uint8Array(lines.length)
    if (options.keyword) for (let n = 0; n < lines.length; n++) if (matches[n] !== null) {
      selected[n] = 1
      for (const direction of [-1, 1]) for (let index = n + direction; index >= 0 && index < lines.length; index += direction) {
        const other = lines[index]
        if (other.separator || other.segment !== lines[n].segment || Math.abs(other.number - lines[n].number) > options.contextLines) break
        selected[index] = 1
      }
    }
    // Separators represent cache segments, not physical file lines.
    const firstSelected = selected.indexOf(1), lastSelected = selected.lastIndexOf(1)
    if (firstSelected !== -1) for (let n = firstSelected + 1; n < lastSelected; n++) if (lines[n].separator) selected[n] = 1
    const result = []
    let chars = 0, overflow = false
    for (let n = 0; n < lines.length; n++) {
      const line = lines[n]
      if (!selected[n]) { line.row = null; continue }
      if (line.separator) {
        const text = line.parts[0].block.text
        if (result.length >= MAX_ROWS || chars + text.length > MAX_CHARS) { overflow = true; continue }
        result.push({ renderKey: line.key, text, separator: true, fragment: true }); chars += text.length
        continue
      }
      if (result.length >= MAX_ROWS) { overflow = true; line.row = null; continue }
      const matched = matches[n] !== null
      if (!line.row || line.rowVersion !== line.version || line.row.matched !== matched) {
        let start = line.start
        if (matched && matches[n] + options.keyword.length > start + PREVIEW_CHARS) start = Math.max(start, matches[n] - Math.min(128, Math.max(0, PREVIEW_CHARS - options.keyword.length)))
        // Move inward at both clipping boundaries to preserve valid pairs and
        // the preview size, without manufacturing replacement characters.
        if (start > line.start && lowSurrogate(slice(line, start, start + 1).charCodeAt(0)) && highSurrogate(slice(line, start - 1, start).charCodeAt(0))) ++start
        const logicalEnd = effectiveEnd(line)
        let end = Math.min(logicalEnd, start + PREVIEW_CHARS)
        if (end < logicalEnd && highSurrogate(slice(line, end - 1, end).charCodeAt(0)) && lowSurrogate(slice(line, end, end + 1).charCodeAt(0))) --end
        const text = slice(line, start, end)
        line.row = { renderKey: line.key, lineNumber: line.number, text, matched, truncated: start > line.start || end < logicalEnd, fragment: true }
        line.rowVersion = line.version
      }
      if (chars + line.row.text.length > MAX_CHARS) { overflow = true; line.row = null; continue }
      result.push(line.row); chars += line.row.text.length
    }
    rows.value = result; limited.value = overflow
    const summaries = new Set([...lines, ...recent, ...(open ? [open] : [])])
    stats.value = { processedChars, rebuilds, retainedBlocks: blocks.length, retainedChars: blocks.reduce((sum, block) => sum + block.text.length, 0), retainedLineSummaries: summaries.size }
  }
  function rebuild(next) {
    ++generation; ++rebuilds; lines = []; recent = []; pool = []; open = null; counts = new Map()
    ordinal = 0; order = 0; position = 0; segment = 0; afterUntil = 0; blocks = next
    for (const block of blocks) append(block)
  }
  function sync(rawBlocks) {
    const next = rawBlocks.slice(-MAX_BLOCKS)
    const head = next.length ? blocks.findIndex(block => sameBlock(block, next[0])) : -1
    const overlap = head < 0 ? 0 : blocks.length - head
    const incremental = head >= 0 && next.length >= overlap && blocks.slice(head).every((block, n) => sameBlock(block, next[n])) &&
      next.slice(overlap).every((block, n) => { const previous = n ? next[overlap + n - 1] : next[overlap - 1]; return previous.separator || block.separator || previous.offset + previous.length === block.offset })
    if (!incremental) rebuild(next)
    else {
      // Preserve fragment ownership if equivalent metadata objects are supplied.
      const retained = [...blocks.slice(head), ...next.slice(overlap)]
      if (head) evict(retained)
      blocks = retained
      for (let n = overlap; n < blocks.length; n++) append(blocks[n])
    }
    publish()
  }
  function configure(value = {}) {
    const next = { keyword: String(value.keyword ?? options.keyword), caseSensitive: Boolean(value.caseSensitive ?? options.caseSensitive), contextLines: value.contextLines === undefined ? options.contextLines : Math.max(0, Math.min(2, Math.floor(Number(value.contextLines) || 0))) }
    if (next.keyword === options.keyword && next.caseSensitive === options.caseSensitive && next.contextLines === options.contextLines) return
    options = next
    matcher = next.keyword ? new RegExp(next.keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), next.caseSensitive ? 'u' : 'iu') : null
    rebuild(blocks); publish()
  }
  function clear() {
    blocks = []; lines = []; recent = []; pool = []; open = null; counts = new Map(); ordinal = 0; order = 0; position = 0; afterUntil = 0; ++generation
    rows.value = []; limited.value = false
    stats.value = { processedChars, rebuilds, retainedBlocks: 0, retainedChars: 0, retainedLineSummaries: 0 }
  }
  return { rows, limited, stats, sync, configure, clear }
}
