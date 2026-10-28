import { computed, ref } from 'vue'

// Network ownership is per generation; late starts/pages can never repaint a new scan.
export function useViewerFilter({ source, api, onError }) {
  const active = ref(false), scanning = ref(false), canceled = ref(false)
  const history = ref([]), rows = ref([])
  const displayRows = computed(() => [...history.value, ...rows.value])
  let segment = 0, pendingReplacement = null
  const offset = ref(0), totalSize = ref(0)
  const eof = ref(false), limited = ref(false), error = ref(''), paused = ref(false)
  const readyForTail = ref(false), mode = ref(''), reason = ref('')
  let generation = 0, cursor = null, stream = null, settle = null
  const release = async entry => {
    if (entry) { try { await api.cancel(entry.id, entry.token) } catch { /* idle TTL is the fallback */ } }
  }
  function merge(page) {
    if ((page.mode === 'native' || page.mode === 'sftp') && (!readyForTail.value || page.reason)) {
      if (typeof page.reason === 'string') reason.value = page.reason
      else if (page.mode !== mode.value) reason.value = ''
      mode.value = page.mode
    }
    if (pendingReplacement) {
      if (pendingReplacement.truncated) history.value.push(...rows.value, { renderKey: `reset:${segment}`, text: '===================truncated===================', separator: true })
      rows.value = []; pendingReplacement = null
    }
    let overflow = false
    for (const row of page.rows || []) {
      // Scanner rows are ordered; binary insertion also handles an updated partial line.
      let lo = 0, hi = rows.value.length
      while (lo < hi) { const mid = (lo + hi) >>> 1; if (rows.value[mid].lineNumber < row.lineNumber) lo = mid + 1; else hi = mid }
      if (rows.value[lo]?.lineNumber === row.lineNumber) rows.value[lo] = { ...row, renderKey: `line:${segment}:${row.lineNumber}` }
      else if (rows.value.length < 10000) rows.value.splice(lo, 0, { ...row, renderKey: `line:${segment}:${row.lineNumber}` })
      else overflow = true
    }
    if (history.value.length + rows.value.length > 10000) history.value.splice(0, history.value.length + rows.value.length - 10000)
    offset.value = page.offset
    totalSize.value = page.totalSize
    eof.value = Boolean(page.eof)
    limited.value = Boolean(page.limited) || overflow
  }
  function stopStream() {
    stream?.close(); stream = null
    settle?.(); settle = null
  }
  function start(options, { preserveHistory = false, truncated = false } = {}) {
    const gen = ++generation
    stopStream()
    const old = cursor; cursor = null
    void release(old)
    active.value = true; scanning.value = true; canceled.value = false
    ++segment
    pendingReplacement = preserveHistory ? { truncated } : null
    if (!preserveHistory) { rows.value = []; history.value = [] }
    offset.value = 0; totalSize.value = 0
    eof.value = false; readyForTail.value = false; mode.value = ''; reason.value = ''; limited.value = false; error.value = ''; paused.value = false
    const done = new Promise(resolve => { settle = resolve })
    const src = source()
    function fail(err) {
      if (gen !== generation) return
      error.value = err.message || '筛选失败，请重新筛选'
      scanning.value = false; readyForTail.value = false
      const old = cursor; cursor = null
      stopStream(); void release(old)
      onError?.(error.value, err)
    }
    void (async () => {
      try {
        const res = await api.start({ ...src, ...options })
        const entry = { id: res.scanId, token: res.sessionToken || src.sessionToken }
        if (gen !== generation) { void release(entry); return }
        cursor = entry; mode.value = res.mode || 'sftp'; reason.value = res.reason || ''
        stream = api.stream({
          onData: page => {
            if (gen !== generation || paused.value || !cursor) return false
            if (page.scanId !== cursor.id || !Number.isSafeInteger(page.offset) || page.offset < 0 || !Number.isSafeInteger(page.totalSize) || page.totalSize < page.offset ||
                !Array.isArray(page.rows) || page.rows.length > 10000 || page.rows.some(row => !Number.isSafeInteger(row.lineNumber) || row.lineNumber < 1 || typeof row.text !== 'string')) {
              throw new Error('筛选数据无效，请重新筛选')
            }
            merge(page)
            return true
          },
          onComplete: () => {
            if (gen !== generation) return
            scanning.value = false
            // complete follows the final ACK and confirms the server released its lease.
            readyForTail.value = eof.value && !limited.value
            stream = null; settle?.(); settle = null
          },
          onRecover: fail,
          onError: (message, frame) => fail({ ...frame, message })
        })
        stream.start({ ...src, sessionToken: entry.token, offset: 0, scanId: entry.id, historical: true })
        if (paused.value) stream.pause()
      } catch (err) { fail(err) }
    })()
    return done
  }
  async function cancel() {
    ++generation
    stopStream()
    const old = cursor; cursor = null
    scanning.value = false; canceled.value = true; readyForTail.value = false
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
    if (value) stream?.pause()
    else stream?.resume()
  }
  return { active, scanning, rows, displayRows, offset, totalSize, eof, readyForTail, mode, reason, limited, error, canceled, paused, start, cancel, close, tailSource, applyTail, pause }
}
