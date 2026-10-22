/**
 * 终端共享会话辅助工具函数
 */

export function buildShareUrl(origin, pathname, token, encoding) {
  const base = (origin || '').replace(/\/+$/, '')
  const path = (pathname || '').replace(/\/+$/, '')
  const encParam = encoding ? `&encoding=${encodeURIComponent(encoding)}` : ''
  return `${base}${path}/#/share?token=${encodeURIComponent(token || '')}${encParam}`
}

export function formatExpireDuration(expireAt, now = Date.now()) {
  if (!expireAt) return '永久有效'
  const diff = Number(expireAt) - Number(now)
  if (diff <= 0) return '已过期'
  const totalMins = Math.floor(diff / 60000)
  if (totalMins < 60) return `${totalMins}分钟有效`
  const hours = Math.floor(totalMins / 60)
  const mins = totalMins % 60
  return `${hours}小时${mins > 0 ? mins + '分' : ''}有效`
}

export function formatSharePermission(permission) {
  const isRo = String(permission).toUpperCase() === 'READ_ONLY'
  return {
    readOnly: isRo,
    label: isRo ? '只读观看' : '实时协同',
    icon: isRo ? '👁️' : '✍️',
    badgeText: isRo ? '👁️ 只读观看' : '✍️ 实时协同'
  }
}

export function buildTerminalWsQuery({ accountId, shareToken, terminalId, cols, rows, transport } = {}) {
  const params = new URLSearchParams()
  if (accountId != null) params.set('accountId', String(accountId))
  if (shareToken) params.set('shareToken', String(shareToken))
  if (terminalId) params.set('terminalId', String(terminalId))
  if (cols > 0) params.set('cols', String(cols))
  if (rows > 0) params.set('rows', String(rows))
  if (!shareToken && transport === 'ssh') params.set('transport', 'ssh')
  return params.toString()
}
