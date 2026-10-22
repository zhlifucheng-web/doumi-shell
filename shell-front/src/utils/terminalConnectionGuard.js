/** Own the deadlines of one WebSocket generation; disposal never affects its replacement. */
export function createTerminalConnectionGuard({
  onFailure, sendPing, startupTimeoutMs = 60_000, heartbeatIntervalMs = 30_000,
  pongTimeoutMs = 15_000, setTimer = setTimeout, clearTimer = clearTimeout
}) {
  let ended = false, ready = false, startupTimer, heartbeatTimer, pongTimer
  const clear = () => {
    clearTimer(startupTimer); clearTimer(heartbeatTimer); clearTimer(pongTimer)
  }
  const fail = message => {
    if (ended) return
    ended = true; clear(); onFailure(message)
  }
  const schedulePing = () => {
    heartbeatTimer = setTimer(() => {
      if (ended) return
      pongTimer = setTimer(() => fail('SSH 连接心跳超时，请重新连接'), pongTimeoutMs)
      sendPing()
    }, heartbeatIntervalMs)
  }
  startupTimer = setTimer(() => fail('终端启动超时，请检查远端 SSH / tmux 后重试'), startupTimeoutMs)
  return {
    ready() {
      if (ended || ready) return
      ready = true; clearTimer(startupTimer); schedulePing()
    },
    pong() {
      if (ended || !ready || !pongTimer) return
      clearTimer(pongTimer); pongTimer = null; schedulePing()
    },
    fail,
    dispose() { ended = true; clear() }
  }
}

export function isExpiredTerminalSession(error) {
  return /会话不存在|会话.*已过期|SSH 未连接/.test(error?.message || '')
}
