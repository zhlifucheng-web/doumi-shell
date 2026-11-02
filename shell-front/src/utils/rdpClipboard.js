const MAX_BYTES = 262143
const CHUNK_BYTES = 6048
const TOO_LARGE = '剪贴板文本过长，最多支持 256 KiB 的 UTF-8 文本。'
const BLOCKED = '浏览器未允许访问剪贴板，请打开工具栏“剪贴板”手动复制或粘贴。'

/** Native paste events avoid read permission prompts; promise-backed writes keep
 * the copy operation inside the user's keyboard gesture (including Safari). */
export function bindRdpClipboard({ Guacamole, client, surface, isEnabled, hasFocus, sendShortcut, onText = () => {}, onNotice = () => {},
  clipboard = globalThis.navigator?.clipboard, ClipboardItem = globalThis.ClipboardItem,
  setTimer = setTimeout, clearTimer = clearTimeout }) {
  let disposed = false, transfer = null, copying = null, pasteTimer = null
  const readers = new Set()
  const enabled = () => !disposed && isEnabled()
  const focused = () => enabled() && hasFocus()
  const notice = text => { if (enabled()) onNotice(text) }
  function clearPasteTimer() { if (pasteTimer !== null) clearTimer(pasteTimer); pasteTimer = null }
  function cancelCopy() {
    const previous = copying; copying = null
    if (previous) { previous.cancelled = true; clearTimer(previous.timer); previous.reject?.(new Error('cancelled')) }
  }
  function beginCopy() {
    cancelCopy()
    const request = {}; copying = request
    request.timer = setTimer(() => { if (copying === request) { cancelCopy(); notice('未收到远程剪贴板文本，请确认已选中文字后重试。') } }, 5000)
    if (clipboard?.write && ClipboardItem) {
      const blob = new Promise((resolve, reject) => { request.resolve = resolve; request.reject = reject })
      blob.catch(() => {})
      try {
        Promise.resolve(clipboard.write([new ClipboardItem({ 'text/plain': blob })])).then(() => {
          if (!request.cancelled) notice('')
        }).catch(() => {
          request.failed = true
          if (!disposed && !request.cancelled) notice(BLOCKED)
        })
      } catch { request.failed = true; notice(BLOCKED) }
    }
  }
  async function copyText(text) {
    if (!enabled()) return false
    try {
      if (!clipboard?.writeText) throw new Error('unavailable')
      await clipboard.writeText(text)
      notice(''); return true
    } catch { notice(BLOCKED); return false }
  }
  function received(text) {
    if (!focused()) { cancelCopy(); return }
    onText(text)
    const request = copying; copying = null
    if (request) clearTimer(request.timer)
    if (request?.resolve && !request.failed) request.resolve(new Blob([text], { type: 'text/plain' }))
    else copyText(text)
  }
  client.onclipboard = (stream, mimetype) => {
    if (mimetype !== 'text/plain') { stream.sendAck('Only text clipboard is supported', 256); return }
    const decoder = new TextDecoder(), chunks = []
    let bytes = 0, rejected = false
    readers.add(stream)
    stream.onblob = value => {
      if (rejected || disposed) return
      try {
        const raw = globalThis.atob(value), data = Uint8Array.from(raw, ch => ch.charCodeAt(0))
        bytes += data.length
        if (bytes > MAX_BYTES) throw new Error('oversized')
        chunks.push(decoder.decode(data, { stream: true }))
        stream.sendAck('OK', 0)
      } catch { rejected = true; chunks.length = 0; stream.sendAck('Clipboard text rejected', 771); notice(TOO_LARGE) }
    }
    stream.onend = () => {
      readers.delete(stream)
      if (!rejected && !disposed) received(chunks.join('') + decoder.decode())
      chunks.length = 0
    }
  }
  async function pasteText(text) {
    if (!focused()) return false
    if (transfer) { notice('正在同步剪贴板，请稍候。'); return false }
    const bytes = new TextEncoder().encode(text)
    if (bytes.length > MAX_BYTES) { notice(TOO_LARGE); return false }
    let completed = false
    const success = await new Promise(resolve => {
      const stream = client.createClipboardStream('text/plain')
      const writer = new Guacamole.ArrayBufferWriter(stream)
      let offset = 0
      const finish = ok => {
        if (completed) return
        completed = true; clearTimer(timer); writer.onack = null; stream.onack = null
        if (!disposed) writer.sendEnd()
        transfer = null; resolve(ok)
      }
      const timer = setTimer(() => { notice('剪贴板同步超时，请重试。'); finish(false) }, 10000)
      transfer = { cancel: () => finish(false) }
      writer.onack = status => {
        if (completed) return
        if (status.isError()) { notice('远程剪贴板不可用，请检查 Windows 剪贴板重定向设置。'); finish(false); return }
        if (offset === bytes.length) { finish(true); return }
        const end = Math.min(offset + CHUNK_BYTES, bytes.length)
        const chunk = bytes.subarray(offset, end); offset = end; writer.sendData(chunk)
      }
    })
    if (success && focused()) { notice(''); sendShortcut(118); return true }
    return false
  }
  function keydown(event) {
    // Reserve Command for browser shortcuts rather than forwarding it as the
    // Windows key, whose release would open Start before a clipboard chord.
    if (focused() && event.key === 'Meta') { event.stopImmediatePropagation(); return }
    if (!focused() || !(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey || event.isComposing) return
    const key = event.key?.toLowerCase()
    if (key !== 'v' && key !== 'c') return
    event.stopImmediatePropagation()
    if (key === 'c') {
      event.preventDefault()
      if (!event.repeat) { beginCopy(); sendShortcut(99) }
    } else {
      // Let the browser emit its native paste event instead of forwarding V
      // before the clipboard has arrived at guacd.
      clearPasteTimer()
      pasteTimer = setTimer(() => { pasteTimer = null; notice(BLOCKED) }, 700)
    }
  }
  function keyup(event) {
    if (focused() && event.key === 'Meta') { event.stopImmediatePropagation(); return }
    if (focused() && (event.ctrlKey || event.metaKey) && ['c','v'].includes(event.key?.toLowerCase())) event.stopImmediatePropagation()
  }
  function paste(event) {
    if (!focused()) return
    clearPasteTimer(); event.preventDefault(); event.stopImmediatePropagation()
    const data = event.clipboardData
    if (data?.files?.length) { notice('当前仅支持文本剪贴板，文件粘贴暂不支持。'); return }
    if (!data || !Array.from(data.types || []).includes('text/plain')) { notice(BLOCKED); return }
    pasteText(data.getData('text/plain'))
  }
  function copy(event) {
    if (!focused()) return
    event.preventDefault(); event.stopImmediatePropagation()
    beginCopy(); sendShortcut(99)
  }
  surface.addEventListener('keydown', keydown, true)
  surface.addEventListener('keyup', keyup, true)
  surface.addEventListener('paste', paste, true)
  surface.addEventListener('copy', copy, true)
  function dispose() {
    if (disposed) return
    disposed = true; clearPasteTimer(); cancelCopy(); transfer?.cancel()
    for (const stream of readers) { stream.onblob = null; stream.onend = null }
    readers.clear(); client.onclipboard = null
    surface.removeEventListener('keydown', keydown, true)
    surface.removeEventListener('keyup', keyup, true)
    surface.removeEventListener('paste', paste, true)
    surface.removeEventListener('copy', copy, true)
  }
  return { pasteText, copyText, dispose }
}
