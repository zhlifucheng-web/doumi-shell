// Mouse reports are terminal protocol bytes, independent of the remote text encoding.
const mouseReport = /^(?:\x1b\[M[\s\S]{3}|\x1b\[<\d+;\d+;\d+[Mm])$/

export function encodeTerminalText(data) {
  const bytes = new TextEncoder().encode(data)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

export function bindTerminalInput(terminal, send, getEncoding) {
  terminal.onData(data => {
    if (data === '\r' || data === '\n' || data === '\r\n') {
      const buffer = terminal.buffer?.active
      if (buffer && buffer.viewportY < buffer.baseY) terminal.scrollToBottom()
    }
    send({ type: 'input', data: encodeTerminalText(data), encoding: mouseReport.test(data) ? 'UTF-8' : getEncoding() })
  })
  // xterm uses onBinary for the original X10 mouse protocol (including tmux 1.6).
  terminal.onBinary(data => send({ type: 'input', data: btoa(data), encoding: 'UTF-8' }))
}
