const abortError = () => Object.assign(new Error('复制已取消'), { name: 'AbortError' })

// Keep the job/session snapshot in the shared transfer queue, independent of the open file panel.
export async function runRemoteCopy(snapshot, { signal, onProgress } = {}, request, wait = ms => new Promise(resolve => setTimeout(resolve, ms))) {
  if (signal?.aborted) throw abortError()
  let id, terminal = false, cancelSent = false
  const cancel = async () => {
    await request('cancel', { sessionToken: snapshot.sessionToken, id })
    cancelSent = true
  }
  try {
    // Do not abort this request: its response identifies the job that must be cleaned up.
    const started = await request('start', snapshot)
    id = started.id
    for (;;) {
      if (signal?.aborted && !cancelSent) await cancel()
      const status = await request('status', { sessionToken: snapshot.sessionToken, id })
      onProgress?.(status.loaded, status.total)
      if (status.status === 'COMPLETED') { terminal = true; return { completed: true, warning: status.warning } }
      if (status.status === 'CANCELLED') {
        terminal = true
        throw Object.assign(abortError(), { cleanupWarning: status.warning })
      }
      if (status.status === 'FAILED') {
        terminal = true
        const error = new Error([status.error, status.warning].filter(Boolean).join('；'))
        if (signal?.aborted) error.cleanupWarning = error.message
        throw error
      }
      await wait(500)
    }
  } catch (error) {
    if (id && !terminal) {
      try { if (!cancelSent) await cancel() } catch { /* Report uncertainty below even when cancellation itself fails. */ }
      error.cleanupWarning = '已尝试取消后台复制，无法确认最终状态，请检查目标目录后再重试。'
    }
    throw error
  }
}
