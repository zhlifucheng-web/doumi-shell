const PREFIX = 'doumi-transfer-v1:'
const ACTIVE = new Set(['等待中', '传输中', '取消中', '保存中', '交给浏览器中'])
export const isTransferActive = task => ACTIVE.has(task.status)

// Store metadata only: never persist SSH tokens, local File objects or filesystem handles.
export function transferSnapshot(task) {
  const { id, name, direction, path, accountId, hostId, sessionId, total, loaded, status, error, createdAt, updatedAt } = task
  return { id, name, direction, path, accountId, hostId, sessionId, total, loaded, status, error, createdAt, updatedAt }
}

export function transfersForSession(tasks, accountId, sessionId) {
  if (accountId == null) return []
  const list = Array.isArray(tasks) ? tasks : (tasks?.value || [])
  return list.filter(task => task.accountId === accountId && (task.sessionId || '') === sessionId)
}

export function transfersForServer(tasks, hostId) {
  if (hostId == null) return []
  const list = Array.isArray(tasks) ? tasks : (tasks?.value || [])
  return list.filter(task => task.hostId === hostId)
}

export function restoreTransfer(record) {
  const task = { ...transferSnapshot(record), speed: 0, restored: true }
  if (record.status === '保存中') {
    task.status = '结果待确认'
    task.error = '上次页面在保存文件时离开，本页无法确认是否提交成功。请检查远程目标或本机文件后再重试。'
  } else if (record.status === '交给浏览器中') {
    task.status = '结果待确认'
    task.error = '无法确认上次是否成功交给浏览器，请检查浏览器下载列表。'
  } else if (ACTIVE.has(record.status)) {
    task.status = '无法恢复'
    task.error = '这是上次页面或其他窗口的记录，本页无法接管或断点续传。原页面仍打开时请返回查看；若已刷新或关闭，请重新选择文件并发起传输。'
  }
  return task
}

export function readTransferHistory(storage) {
  const history = []
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i)
    if (!key?.startsWith(PREFIX)) continue
    try {
      const record = JSON.parse(storage.getItem(key))
      if (!record || typeof record.id !== 'string' || typeof record.status !== 'string'
          || typeof record.name !== 'string' || !Number.isFinite(record.loaded) || record.loaded < 0
          || !Number.isFinite(record.total) || record.total < 0) continue
      history.push(record)
    } catch { /* An invalid record must not prevent the file page from opening. */ }
  }
  return history.sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0)).slice(-200).map(restoreTransfer)
}

export function saveTransferHistory(storage, task) {
  storage.setItem(PREFIX + task.id, JSON.stringify(transferSnapshot(task)))
}
export function saveTransferHost(storage, task) {
  const key = PREFIX + task.id
  const saved = storage.getItem(key)
  if (!saved) return
  const record = JSON.parse(saved)
  if (record.hostId != null) return
  // A restored record may still be active in another browser window; update ownership only.
  record.hostId = task.hostId
  storage.setItem(key, JSON.stringify(record))
}
export function removeTransferHistory(storage, task) { storage.removeItem(PREFIX + task.id) }

export function trimTransferHistory(storage) {
  const keys = []
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i)
    if (key?.startsWith(PREFIX)) keys.push(key)
  }
  keys.sort((a, b) => {
    try { return (JSON.parse(storage.getItem(a))?.createdAt || 0) - (JSON.parse(storage.getItem(b))?.createdAt || 0) }
    catch { return 0 }
  })
  for (const key of keys.slice(0, Math.max(0, keys.length - 200))) storage.removeItem(key)
}

export function transferErrorMessage(error, direction) {
  const body = error?.response?.data
  const raw = [body?.externMessage, body?.message, error?.message].filter(Boolean).join('；')
  if (/会话不存在|会话已失效|已过期|SSH.*断开|not connected|connection.*closed|socket.*closed/i.test(raw))
    return `SSH 会话已断开或过期，本任务无法切换到新连接继续。请重新连接后重新${direction === '上传' ? '选择文件上传' : '发起下载'}。${direction === '上传' ? '若在保存时断开，请先检查远程目标是否已更新。' : ''}`
  if (error?.name === 'NotAllowedError' || error?.name === 'SecurityError')
    return '浏览器不允许访问所选文件或目录，请重新选择保存位置并授权；浏览器不支持时请使用原生下载。'
  if (error?.name === 'QuotaExceededError') return '本机存储空间或浏览器配额不足，请清理空间后重新下载。'
  if ((error?.isAxiosError && !error.response) || (error?.name === 'TypeError' && /fetch|network/i.test(raw)))
    return '网络连接中断或服务不可用，本任务不能自动续传。请检查连接后重新发起；若在保存时中断，请先检查目标文件。'
  return raw || '传输失败，请检查连接和磁盘空间后重新发起。'
}
