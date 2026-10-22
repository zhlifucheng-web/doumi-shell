/** 上次在 SSH 服务器页选中的账户（与 T2 currentServer 独立） */
export const LS_SSH_LAST_ACCOUNT = 'sshConsoleLastAccount'

export function readSavedSshAccount() {
  try {
    const raw = localStorage.getItem(LS_SSH_LAST_ACCOUNT)
    if (!raw) return null
    const o = JSON.parse(raw)
    if (o && o.nodeType === 'ACCOUNT' && o.id != null) {
      return {
        nodeType: 'ACCOUNT',
        id: Number(o.id),
        key: o.key || `a:${o.id}`,
        hostId: o.hostId != null ? Number(o.hostId) : null,
        groupId: o.groupId != null ? Number(o.groupId) : null,
        label: o.label || ''
      }
    }
  } catch {
    /* ignore */
  }
  return null
}

export function writeSavedSshAccount(payload) {
  if (!payload?.id) return
  localStorage.setItem(
    LS_SSH_LAST_ACCOUNT,
    JSON.stringify({
      nodeType: 'ACCOUNT',
      id: payload.id,
      key: payload.key || `a:${payload.id}`,
      hostId: payload.hostId ?? null,
      groupId: payload.groupId ?? null,
      label: payload.label || ''
    })
  )
}

export function clearSavedSshAccount() {
  localStorage.removeItem(LS_SSH_LAST_ACCOUNT)
}

/** 终端标签页会话持久化（保存在 sessionStorage 中，网页刷新后保持已有标签与 tmux 会话） */
export const SS_SSH_TERMINAL_TABS = 'sshConsoleTerminalTabs'
export const SS_SSH_ACTIVE_TERMINAL_ID = 'sshConsoleActiveTerminalId'

export function saveTerminalTabs(tabs, activeTabId, storage = (typeof sessionStorage !== 'undefined' ? sessionStorage : null)) {
  if (!storage) return
  try {
    if (!Array.isArray(tabs)) return
    const serialized = tabs.map(t => ({
      tabId: t.tabId,
      terminalId: t.terminalId || 'default',
      accountId: t.accountId,
      hostId: t.hostId,
      defaultCwd: t.defaultCwd || '',
      label: t.label || '',
      encoding: t.encoding || 'UTF-8',
      account: t.account || null
    }))
    storage.setItem(SS_SSH_TERMINAL_TABS, JSON.stringify(serialized))
    if (activeTabId != null) {
      storage.setItem(SS_SSH_ACTIVE_TERMINAL_ID, String(activeTabId))
    }
  } catch {
    /* ignore storage quota errors */
  }
}

export function readSavedTerminalTabs(storage = (typeof sessionStorage !== 'undefined' ? sessionStorage : null)) {
  if (!storage) return { tabs: [], activeTabId: null }
  try {
    const raw = storage.getItem(SS_SSH_TERMINAL_TABS)
    if (!raw) return { tabs: [], activeTabId: null }
    const parsed = JSON.parse(raw)
    const activeTabId = storage.getItem(SS_SSH_ACTIVE_TERMINAL_ID)
    if (!Array.isArray(parsed)) return { tabs: [], activeTabId: null }
    const tabs = parsed
      .filter(t => t && t.accountId && t.tabId)
      .map(t => ({
        tabId: String(t.tabId),
        terminalId: String(t.terminalId || 'default'),
        accountId: Number(t.accountId),
        hostId: t.hostId != null ? Number(t.hostId) : null,
        defaultCwd: t.defaultCwd || '',
        label: t.label || '',
        encoding: t.encoding || 'UTF-8',
        account: t.account || null,
        token: '',
        connectionMode: ''
      }))
    return {
      tabs,
      activeTabId: activeTabId ? String(activeTabId) : (tabs[0]?.tabId || null)
    }
  } catch {
    return { tabs: [], activeTabId: null }
  }
}

export function clearSavedTerminalTabs(storage = (typeof sessionStorage !== 'undefined' ? sessionStorage : null)) {
  if (!storage) return
  try {
    storage.removeItem(SS_SSH_TERMINAL_TABS)
    storage.removeItem(SS_SSH_ACTIVE_TERMINAL_ID)
  } catch {
    /* ignore */
  }
}

/**
 * 分配确定性、可持久化重连的终端会话 ID：
 * - 同一账户的首个主终端使用稳定标识 'default'（对应 tmux 会话 doumi-shell-{accountId}-default）
 * - 额外新建的终端使用递增编号 'term-2', 'term-3' 等（对应 tmux 会话 doumi-shell-{accountId}-term-2）
 */
export function allocateTerminalId(accountId, existingTabs = []) {
  const accountTabs = Array.isArray(existingTabs)
    ? existingTabs.filter(t => t && t.accountId === accountId)
    : []
  const usedSuffixes = new Set(accountTabs.map(t => t.terminalId || 'default'))

  if (!usedSuffixes.has('default')) {
    return {
      tabId: `term-${accountId}-default`,
      terminalId: 'default',
      subIndex: 1
    }
  }

  let idx = 2
  while (usedSuffixes.has(`term-${idx}`)) {
    idx++
  }
  return {
    tabId: `term-${accountId}-term-${idx}`,
    terminalId: `term-${idx}`,
    subIndex: idx
  }
}

/** 懒加载树展开到主机：祖先分组 g:* + 主机 h:* */
export async function resolveSshExpandedKeys(selection, { getGroup, getHost }) {
  if (!selection) return []
  const keys = []
  let groupId = selection.groupId ?? null
  let hostId = selection.hostId ?? null

  if (selection.nodeType === 'ACCOUNT' && hostId) {
    keys.push(`h:${hostId}`)
    if (groupId == null) {
      try {
        const h = await getHost(hostId)
        groupId = h?.groupId ?? null
      } catch {
        groupId = null
      }
    }
  } else if (selection.nodeType === 'HOST' && selection.id) {
    hostId = selection.id
    keys.push(`h:${hostId}`)
    if (groupId == null) {
      try {
        const h = await getHost(hostId)
        groupId = h?.groupId ?? null
      } catch {
        groupId = null
      }
    }
  }

  const groupKeys = []
  let gid = groupId
  const seen = new Set()
  while (gid != null && !seen.has(gid)) {
    seen.add(gid)
    groupKeys.unshift(`g:${gid}`)
    try {
      const g = await getGroup(gid)
      gid = g?.parentId ?? null
    } catch {
      break
    }
  }
  return [...groupKeys, ...keys]
}
