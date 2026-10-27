// File viewers may outlive the terminal token they were opened with.
export function isViewerSessionError(error) {
  return ['SESSION_EXPIRED', 'SSH_DISCONNECTED'].includes(error?.code) || /会话不存在或已过期|SSH 会话已失效|SSH 连接已断开|not connected|connection (?:is )?closed|transport.*(?:closed|disconnected)/i.test(error?.message || '')
}

export function createViewerSession({ token, accountId, api, onToken }) {
  let current = token, account = Number(accountId) || 0, owned = false, closed = false, connecting
  const release = value => { if (value) void api.close(value).catch(() => {}) }
  const context = !account && current ? api.context(current).then(value => { account = Number(value.accountId) || 0 }).catch(() => {}) : Promise.resolve()
  async function reconnect(expired = current) {
    await context
    if (closed) throw new Error('文件窗口已关闭')
    if (current !== expired) return current
    if (!account) throw new Error('无法恢复此旧窗口的账户信息，请从文件列表重新打开')
    if (!connecting) connecting = (async () => {
      const result = await api.open(account)
      if (!result?.sessionToken) throw new Error('SSH 重连未返回有效会话')
      if (closed) { release(result.sessionToken); throw new Error('文件窗口已关闭') }
      if (owned) release(current)
      current = result.sessionToken; owned = true; onToken?.(current)
      return current
    })().finally(() => { connecting = null })
    return connecting
  }
  async function run(action) {
    await context
    if (closed) throw new Error('文件窗口已关闭')
    const used = current
    try { return await action(used) }
    catch (error) {
      if (!isViewerSessionError(error)) throw error
      return action(await reconnect(used))
    }
  }
  function close() { closed = true; if (owned) { release(current); owned = false } }
  return { run, reconnect, close, get token() { return current } }
}
