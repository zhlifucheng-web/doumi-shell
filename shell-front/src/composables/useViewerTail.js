// One server batch may be pending. Only accepted data advances the resume sequence.
export function createViewerTail({ url, onData, onError, onStatus, connect = url => new WebSocket(url), schedule = setTimeout, unschedule = clearTimeout }) {
  let socket, retry, source, tailId, sequence = 0, generation = 0, attempts = 0
  let stopped = true, paused = false, pending = null, applying = false
  function send(frame) { if (socket?.readyState === 1) socket.send(JSON.stringify(frame)) }
  function close() {
    stopped = true; ++generation
    if (retry != null) unschedule(retry)
    retry = null; send({ type: 'close' }); socket?.close(); socket = null
    pending = null; tailId = null; applying = false
  }
  function fail(message) { close(); onStatus?.('stopped'); onError?.(message) }
  async function drain() {
    if (applying || paused || !pending || stopped) return
    const gen = generation, page = pending
    applying = true
    try {
      const accepted = await onData(page)
      if (gen !== generation || stopped) return
      if (accepted !== false) {
        sequence = page.sequence; pending = null
        send({ type: 'ack', sequence })
        if (page.limited) { close(); onStatus?.('stopped') }
      }
    } catch (error) { if (gen === generation) fail(error.message || '实时追踪中断') }
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
      } else if (frame.type === 'error') fail(frame.message || '实时追踪中断，请刷新文件')
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
    if (++attempts > 5) { fail('实时追踪连接中断，请刷新文件或重新筛选'); return }
    onStatus?.('reconnecting')
    retry = schedule(() => { retry = null; if (!stopped && gen === generation) open() }, Math.min(attempts * 1000, 5000))
  }
  function start(options) {
    close(); source = options; sequence = 0; attempts = 0; stopped = false; paused = false
    onStatus?.('connecting'); open()
  }
  function pause() { paused = true; send({ type: 'pause' }); onStatus?.('paused') }
  function resume() { if (stopped) return; paused = false; send({ type: 'resume' }); void drain(); onStatus?.('following') }
  return { start, pause, resume, close }
}
