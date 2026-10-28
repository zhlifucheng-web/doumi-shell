// One server batch may be pending. Only accepted data advances the resume sequence.
export function createViewerTail({ url, onData, onError, onStatus, onRecover, onComplete, connect = url => new WebSocket(url), schedule = setTimeout, unschedule = clearTimeout }) {
  let socket, retry, source, tailId, sequence = 0, generation = 0, attempts = 0
  let stopped = true, paused = false, pending = null, applying = false, terminal = false
  function send(frame) { if (socket?.readyState === 1) socket.send(JSON.stringify(frame)) }
  function close(notify = true) {
    stopped = true; ++generation
    if (retry != null) unschedule(retry)
    retry = null; if (notify) send({ type: 'close' }); socket?.close(); socket = null
    pending = null; tailId = null; applying = false
  }
  function fail(message, frame) { close(); onStatus?.('stopped'); onError?.(message, frame) }
  function recover(frame) {
    if (!onRecover || !['FILE_CHANGED', 'SESSION_EXPIRED', 'SSH_DISCONNECTED', 'TAIL_EXPIRED'].includes(frame.code)) return false
    close(); onStatus?.('reconnecting'); onRecover(frame)
    return true
  }
  async function drain() {
    if (applying || paused || !pending || stopped) return
    const gen = generation, page = pending
    applying = true
    try {
      const accepted = await onData(page)
      if (gen !== generation || stopped) return
      if (accepted !== false) {
        sequence = page.sequence; pending = null
        terminal = Boolean(page.eof || page.limited)
        send({ type: 'ack', sequence })
        if (page.limited && !source.historical) { close(); onStatus?.('stopped') }
      }
    } catch (error) { if (gen === generation && !recover(error)) fail(error.message || '实时追踪中断', error) }
    finally { if (gen === generation) applying = false }
  }
  function open() {
    const gen = generation
    try { socket = connect(url()) } catch { reconnect(gen); return }
    const current = socket
    current.onopen = () => {
      if (gen !== generation || stopped || socket !== current) return
      send(tailId ? { type: 'resume', sessionToken: source.sessionToken, tailId, sequence } : { ...source, type: 'start' })
    }
    current.onmessage = event => {
      if (gen !== generation || stopped || socket !== current) return
      let frame
      try { frame = JSON.parse(event.data) } catch { fail('实时追踪返回了无效数据'); return }
      if (frame.type === 'ready') {
        if (typeof frame.tailId !== 'string' || !frame.tailId || (tailId && tailId !== frame.tailId)) { fail('实时追踪订阅无效，请刷新文件'); return }
        tailId = frame.tailId; attempts = 0; onStatus?.(paused ? 'paused' : 'following')
        if (paused) send({ type: 'pause' })
      } else if (frame.type === 'error') {
        if (!recover(frame)) fail(frame.message || '实时追踪中断，请刷新文件', frame)
      }
      else if (frame.type === 'complete') {
        if (!source.historical || !terminal || pending) { fail('筛选结束状态无效，请重新筛选'); return }
        close(false); onStatus?.('stopped'); onComplete?.()
      }
      else if (frame.type === 'data') {
        if (!Number.isSafeInteger(frame.sequence) || frame.sequence < 1) { fail('实时追踪序号无效'); return }
        if (frame.sequence <= sequence) { send({ type: 'ack', sequence }); return }
        if (frame.sequence !== sequence + 1) { fail('实时追踪数据序号不连续，请刷新文件'); return }
        if (pending && pending.sequence !== frame.sequence) { fail('实时追踪数据顺序异常，请刷新文件'); return }
        pending = frame; void drain()
      }
    }
    current.onclose = () => { if (gen === generation && !stopped && socket === current) reconnect(gen) }
    current.onerror = () => { /* close event drives reconnect */ }
  }
  function reconnect(gen) {
    if (stopped || gen !== generation || retry != null) return
    ++attempts
    onStatus?.('reconnecting')
    retry = schedule(() => { retry = null; if (!stopped && gen === generation) open() }, Math.min(attempts * 1000, 5000))
  }
  function start(options) {
    close(); source = options; sequence = 0; attempts = 0; terminal = false; stopped = false; paused = false
    onStatus?.('connecting'); open()
  }
  function pause() { paused = true; send({ type: 'pause' }); onStatus?.('paused') }
  function resume() { if (stopped) return; paused = false; send({ type: 'resume' }); void drain(); onStatus?.('following') }
  return { start, pause, resume, close }
}
