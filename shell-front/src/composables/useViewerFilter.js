import { ref } from 'vue'

// Network ownership is per generation; late starts/pages can never repaint a new scan.
export function useViewerFilter({ source, api, onError }) {
  const active = ref(false), scanning = ref(false), canceled = ref(false)
  const rows = ref([]), offset = ref(0), totalSize = ref(0)
  const eof = ref(false), limited = ref(false), error = ref(''), paused = ref(false)
  const readyForTail = ref(false)
  let generation = 0, cursor = null, pumping = false
  const release = async entry => {
    if (entry) { try { await api.cancel(entry.id, entry.token) } catch { /* idle TTL is the fallback */ } }
  }
  function merge(page) {
    let overflow = false
    for (const row of page.rows || []) {
      // Scanner rows are ordered; binary insertion also handles an updated partial line.
      let lo = 0, hi = rows.value.length
      while (lo < hi) { const mid = (lo + hi) >>> 1; if (rows.value[mid].lineNumber < row.lineNumber) lo = mid + 1; else hi = mid }
      if (rows.value[lo]?.lineNumber === row.lineNumber) rows.value[lo] = row
      else if (rows.value.length < 10000) rows.value.splice(lo, 0, row)
      else overflow = true
    }
    offset.value = page.offset
    totalSize.value = page.totalSize
    eof.value = Boolean(page.eof)
    if (eof.value) readyForTail.value = true
    limited.value = Boolean(page.limited) || overflow
  }
  async function pump(gen, historical) {
    if (pumping || gen !== generation || !cursor || paused.value) return
    pumping = true
    scanning.value = true
    try {
      do {
        const page = await api.next(cursor.id, cursor.token)
        if (gen !== generation) return
        merge(page)
      } while (historical && !eof.value && !limited.value && !paused.value && gen === generation)
    } catch (err) {
      if (gen === generation) {
        error.value = err.message || '筛选失败，请重新筛选'
        const old = cursor; cursor = null
        void release(old)
        onError?.(error.value)
      }
    } finally {
      // pumping belongs to this operation, including cancellation while in flight.
      pumping = false
      if (gen === generation) scanning.value = false
    }
  }
  async function start(options) {
    const gen = ++generation
    const old = cursor; cursor = null
    void release(old)
    active.value = true; scanning.value = true; canceled.value = false
    rows.value = []; offset.value = 0; totalSize.value = 0
    eof.value = false; readyForTail.value = false; limited.value = false; error.value = ''; paused.value = false
    const src = source()
    try {
      const res = await api.start({ ...src, ...options })
      const entry = { id: res.scanId, token: src.sessionToken }
      if (gen !== generation) { await release(entry); return }
      cursor = entry
      // A canceled generation may still be finishing its request. Wait without
      // overlapping next requests or borrowing its completion state.
      while (pumping && gen === generation) await new Promise(resolve => setTimeout(resolve, 10))
      if (gen === generation) await pump(gen, true)
    } catch (err) {
      if (gen === generation) { error.value = err.message || '无法开始筛选'; scanning.value = false; onError?.(error.value) }
    }
  }
  async function cancel() {
    ++generation
    const old = cursor; cursor = null
    scanning.value = false; canceled.value = true
    await release(old)
  }
  async function close() {
    active.value = false
    await cancel()
  }
  function tailSource() {
    return active.value && readyForTail.value && !limited.value && !canceled.value && !error.value && cursor
      ? { scanId: cursor.id } : null
  }
  function applyTail(page) {
    if (!tailSource() || page.scanId !== cursor.id || paused.value) return false
    merge(page)
    return true
  }
  function pause(value) {
    paused.value = value
    if (!value && active.value && !readyForTail.value && !limited.value && !error.value && !canceled.value) void pump(generation, true)
  }
  return { active, scanning, rows, offset, totalSize, eof, readyForTail, limited, error, canceled, paused, start, cancel, close, tailSource, applyTail, pause }
}
