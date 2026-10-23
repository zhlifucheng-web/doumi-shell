export const TERMINAL_SCROLLBACK = 50_000

export function createHistoryRequest() {
  let sequence = 0, pending = null
  return {
    start() { pending = ++sequence; return pending },
    accept(id) {
      if (pending === null || id !== pending) return false
      pending = null
      return true
    },
    cancel() { pending = null }
  }
}

export function findHistoryMatches(lines, query, limit = 10_000) {
  const matches = []
  if (!query) return { matches, limited: false }
  const pattern = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi')
  for (let row = 0; row < lines.length; row++) {
    pattern.lastIndex = 0
    let match
    while ((match = pattern.exec(lines[row])) !== null) {
      if (matches.length === limit) return { matches, limited: true }
      matches.push({ row, start: match.index, end: match.index + match[0].length })
    }
  }
  return { matches, limited: false }
}

export function historyLineParts(line, matches) {
  const parts = []
  let from = 0
  for (const match of matches) {
    if (match.start > from) parts.push({ text: line.slice(from, match.start), match: false })
    parts.push({ text: line.slice(match.start, match.end), match: true })
    from = match.end
  }
  if (from < line.length) parts.push({ text: line.slice(from), match: false })
  return parts
}
