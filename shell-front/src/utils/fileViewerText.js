export function searchMatches(text, keyword, caseSensitive = false) {
  if (!keyword) return []
  const escaped = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return Array.from(String(text).matchAll(new RegExp(escaped, caseSensitive ? 'gu' : 'giu')), m => ({ index: m.index, length: m[0].length }))
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
}

export function highlightText(text, keyword, current = -1, baseIndex = 0) {
  let end = 0
  const parts = searchMatches(text, keyword).map((match, i) => {
    const part = escapeHtml(text.slice(end, match.index))
      + `<mark data-search-index="${i + baseIndex}" class="search-match${i + baseIndex === current ? ' is-current' : ''}">${escapeHtml(text.slice(match.index, match.index + match.length))}</mark>`
    end = match.index + match.length
    return part
  })
  return parts.join('') + escapeHtml(text.slice(end))
}

export function nextMatch(current, count, direction = 1) {
  if (!count) return -1
  if (current < 0) return direction > 0 ? 0 : count - 1
  return (current + direction + count) % count
}

export function interceptFind(event, focus) {
  if (!(event.ctrlKey || event.metaKey) || event.altKey || event.key?.toLowerCase() !== 'f') return false
  event.preventDefault()
  event.stopPropagation()
  focus()
  return true
}

// Match and render each bounded block independently. A keyword-sized lookahead
// joins matches across batches; unchanged blocks never revisit their old matches.
export function createViewerBlockRenderer() {
  let cache = new Map()
  return (items, keyword = '', current = -1, filtered = false, caseSensitive = false) => {
    const texts = items.map((item, i) => filtered ? item.text + (i < items.length - 1 ? '\n' : '') : item.text)
    const next = new Map(), blocks = []
    let remaining = 0, count = 0, previousLine = 0
    for (let i = 0; i < items.length; i++) {
      const item = items[i], text = texts[i]
      const key = item.renderKey || `${filtered ? 'line' : 'byte'}:${filtered ? item.lineNumber : item.offset}`
      const gap = Boolean(filtered && previousLine && item.lineNumber > previousLine + 1)
      let ahead = ''
      if (keyword.length > 1) for (let n = i + 1; n < texts.length && ahead.length < keyword.length - 1; n++) ahead += texts[n].slice(0, keyword.length - 1 - ahead.length)
      const consumed = Math.min(remaining, text.length), old = cache.get(key)
      let scan = old?.scan
      if (!scan || scan.text !== text || scan.ahead !== ahead || scan.keyword !== keyword || scan.remaining !== remaining || scan.caseSensitive !== caseSensitive) {
        const matches = keyword ? searchMatches(text.slice(consumed) + ahead, keyword, caseSensitive).filter(m => m.index + consumed < text.length) : []
        const last = matches.at(-1)
        scan = { text, ahead, keyword, remaining, caseSensitive, matches, consumed,
          nextRemaining: Math.max(0, remaining - text.length, last ? consumed + last.index + last.length - text.length : 0) }
      }
      let block = old?.block
      if (!block || old.scan !== scan || old.base !== count || old.current !== current || old.gap !== gap || old.matched !== item.matched || old.truncated !== item.truncated || old.fragment !== item.fragment) {
        let end = 0, html = ''
        function mark(start, stop, index) {
          html += escapeHtml(text.slice(end, start)) + `<mark data-search-index="${index}" class="search-match${index === current ? ' is-current' : ''}">${escapeHtml(text.slice(start, stop))}</mark>`
          end = stop
        }
        if (consumed) mark(0, consumed, count - 1)
        for (let n = 0; n < scan.matches.length; n++) {
          const match = scan.matches[n], start = consumed + match.index
          mark(start, Math.min(text.length, start + match.length), count + n)
        }
        html += escapeHtml(text.slice(end))
        if (filtered && !item.separator) html = `${gap ? '<span class="line-gap" aria-hidden="true">⋯</span>\n' : ''}<span class="filter-line${item.matched ? ' is-matched' : ''}${item.fragment ? ' is-fragment' : ''}" title="${item.fragment ? '片段行号 ' : '文件行号 '}${Number(item.lineNumber)}" data-line="${item.fragment ? '片段 ' : ''}${Number(item.lineNumber)}">${item.truncated ? '<span class="line-truncated-note" title="整行已参与匹配，当前只展示有界片段">[超长行，部分内容省略] </span>' : ''}${html}</span>`
        block = { key, html }
      }
      next.set(key, { scan, base: count, current, gap, matched: item.matched, truncated: item.truncated, fragment: item.fragment, block })
      blocks.push(block); count += scan.matches.length; remaining = scan.nextRemaining; previousLine = item.lineNumber
    }
    blocks.matchCount = count; cache = next
    return blocks
  }
}
