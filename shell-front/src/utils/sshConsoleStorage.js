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

/** 本地账户默认终端会话 Key 映射（避免多人全局使用默认 default 导致串 session） */
export const LS_ACCOUNT_TERMINAL_SESSION_MAP = 'sshConsoleAccountTerminalSessionMap'

/**
 * 生成随机唯一的终端会话 Key（8-10位字母数字，tmux与URL安全）
 */
export function generateTerminalSessionKey() {
  const rand = Math.random().toString(36).slice(2, 8)
  const time = Date.now().toString(36).slice(-4)
  return `s_${time}${rand}`
}

export function getAccountSavedTerminalKey(accountId, storage = (typeof localStorage !== 'undefined' ? localStorage : null)) {
  if (!storage || !accountId) return null
  try {
    const raw = storage.getItem(LS_ACCOUNT_TERMINAL_SESSION_MAP)
    if (!raw) return null
    const map = JSON.parse(raw)
    const val = map && map[accountId]
    return (typeof val === 'string' && val.trim() && val.trim() !== 'default') ? val.trim() : null
  } catch {
    return null
  }
}

export function saveAccountTerminalKey(accountId, terminalKey, storage = (typeof localStorage !== 'undefined' ? localStorage : null)) {
  if (!storage || !accountId || !terminalKey) return
  try {
    const raw = storage.getItem(LS_ACCOUNT_TERMINAL_SESSION_MAP)
    const map = raw ? JSON.parse(raw) : {}
    map[accountId] = terminalKey
    storage.setItem(LS_ACCOUNT_TERMINAL_SESSION_MAP, JSON.stringify(map))
  } catch {
    /* ignore storage quota errors */
  }
}

export function removeAccountTerminalKey(accountId, storage = (typeof localStorage !== 'undefined' ? localStorage : null)) {
  if (!storage || !accountId) return
  try {
    const raw = storage.getItem(LS_ACCOUNT_TERMINAL_SESSION_MAP)
    if (!raw) return
    const map = JSON.parse(raw)
    delete map[accountId]
    storage.setItem(LS_ACCOUNT_TERMINAL_SESSION_MAP, JSON.stringify(map))
  } catch {
    /* ignore */
  }
}

export function saveTerminalTabs(tabs, activeTabId, storage = (typeof sessionStorage !== 'undefined' ? sessionStorage : null)) {
  if (!storage) return
  try {
    if (!Array.isArray(tabs)) return
    const serialized = tabs.map(t => ({
      tabId: t.tabId,
      terminalId: (t.terminalId && t.terminalId !== 'default') ? t.terminalId : generateTerminalSessionKey(),
      accountId: t.accountId,
      hostId: t.hostId,
      defaultCwd: t.defaultCwd || '',
      label: t.label || '',
      customTitle: t.customTitle || '',
      subIndex: t.subIndex != null ? Number(t.subIndex) : 1,
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
      .map(t => {
        let termId = String(t.terminalId || '').trim()
        if (!termId || termId === 'default') {
          termId = generateTerminalSessionKey()
        }
        return {
          tabId: String(t.tabId),
          terminalId: termId,
          accountId: Number(t.accountId),
          hostId: t.hostId != null ? Number(t.hostId) : null,
          defaultCwd: t.defaultCwd || '',
          label: t.label || '',
          customTitle: t.customTitle || '',
          subIndex: t.subIndex != null ? Number(t.subIndex) : 1,
          encoding: t.encoding || 'UTF-8',
          account: t.account || null,
          token: '',
          connectionMode: ''
        }
      })
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
 * 分配独立且本地持久化的终端会话 ID：
 * - 避免使用全局固定的 'default'，防止不同用户在未分享时意外加入同一 tmux 会话
 * - 首个主终端优先恢复当前本地已记录的专属随机 key（不存在则生成全新随机 key 并记录于 localStorage）
 * - 额外新建的终端（点击"+"或右键新建）每次分配全新独立随机 key
 * - 统一在 sessionStorage 中保持当前窗口活跃标签页列表，支持刷新页面保持重连
 */
export function allocateTerminalId(accountId, existingTabs = [], options = {}) {
  const accountTabs = Array.isArray(existingTabs)
    ? existingTabs.filter(t => t && t.accountId === accountId)
    : []
  const usedKeys = new Set(accountTabs.map(t => t.terminalId).filter(Boolean))

  const isPrimary = accountTabs.length === 0
  let terminalId = null

  const storage = options.localStorage !== undefined
    ? options.localStorage
    : (typeof localStorage !== 'undefined' ? localStorage : null)

  if (isPrimary && !options.forceNew) {
    const savedKey = getAccountSavedTerminalKey(accountId, storage)
    if (savedKey && savedKey !== 'default' && !usedKeys.has(savedKey)) {
      terminalId = savedKey
    }
  }

  if (!terminalId) {
    do {
      terminalId = generateTerminalSessionKey()
    } while (usedKeys.has(terminalId))

    if (isPrimary && !options.forceNew) {
      saveAccountTerminalKey(accountId, terminalId, storage)
    }
  }

  const subIndex = accountTabs.length + 1
  return {
    tabId: `term-${accountId}-${terminalId}`,
    terminalId,
    subIndex
  }
}

/** 懒加载树展开到目标节点：祖先分组 g:* + 主机 h:* */
export async function resolveSshExpandedKeys(selection, { getGroup, getHost } = {}) {
  if (!selection) return []
  const keys = []
  let groupId = selection.groupId ?? selection.extra?.groupId ?? null
  let hostId = selection.hostId ?? selection.extra?.hostId ?? null

  if (selection.nodeType === 'ACCOUNT') {
    if (hostId) keys.push(`h:${hostId}`)
    if (groupId == null && hostId && getHost) {
      try {
        const h = await getHost(hostId)
        groupId = h?.groupId ?? null
      } catch {
        groupId = null
      }
    }
  } else if (selection.nodeType === 'HOST') {
    if (selection.id) {
      hostId = selection.id
      keys.push(`h:${hostId}`)
    }
    if (groupId == null && hostId && getHost) {
      try {
        const h = await getHost(hostId)
        groupId = h?.groupId ?? null
      } catch {
        groupId = null
      }
    }
  } else if (selection.nodeType === 'GROUP') {
    let parentId = selection.parentId ?? selection.extra?.parentId ?? null
    if (parentId == null && selection.id && getGroup) {
      try {
        const g = await getGroup(selection.id)
        parentId = g?.parentId ?? null
      } catch {
        parentId = null
      }
    }
    groupId = parentId
  }

  const groupKeys = []
  let gid = groupId
  const seen = new Set()
  while (gid != null && !seen.has(gid)) {
    seen.add(gid)
    groupKeys.unshift(`g:${gid}`)
    if (!getGroup) break
    try {
      const g = await getGroup(gid)
      gid = g?.parentId ?? null
    } catch {
      break
    }
  }
  return [...groupKeys, ...keys]
}

/**
 * 格式化终端标签页紧凑显示标题：
 * - 用户自定义别名优先（支持双击/右键重命名）
 * - 去除多余冗长的 (IP:端口) 嵌套后缀（IP 保留在 Tooltip 中查看）
 * - 智能拼接简洁的主机名与账户名
 * - 支持子终端编号 (#2, #3...)
 */
export function formatTerminalTabDisplayLabel(tab, hostMeta = null) {
  if (!tab) return '终端'
  if (tab.customTitle && tab.customTitle.trim()) {
    return tab.customTitle.trim()
  }
  let hostName = (tab.account?.name || (hostMeta?.id === tab.hostId ? hostMeta?.name : '') || tab.account?.host || tab.label?.split(' · ')[0] || '终端').trim()
  hostName = hostName.replace(/\s*\([^)]*\)\s*$/, '').trim() || hostName

  const username = tab.account?.username || ''
  const sub = (tab.subIndex && tab.subIndex > 1) ? ` #${tab.subIndex}` : ''

  if (hostName && username && hostName !== username) {
    return `${hostName} · ${username}${sub}`
  }
  return `${hostName || username || 'SSH'}${sub}`
}

/**
 * 提取终端会话的登录账户名
 */
export function getTabUsername(tab) {
  if (!tab) return 'user'
  return (tab.account?.username || 'user').trim()
}

/**
 * 提取侧边栏收起 (Mini-Dock) 时极简主机标识 (2-4字符)：
 * - 自定义别名优先
 * - IP 地址提取末段 (如 192.168.1.105 -> .105)
 * - 英文单词取前 4 字符 (如 Web1, Redis)
 * - 中文字符取前 2-3 字符 (如 网关, 核心)
 */
export function getTabMiniHost(tab, hostMeta = null) {
  if (!tab) return 'SSH'
  if (tab.customTitle && tab.customTitle.trim()) {
    const title = tab.customTitle.trim()
    return title.length <= 3 ? title : title.slice(0, 3)
  }
  const rawHost = (tab.account?.name || (hostMeta?.id === tab.hostId ? hostMeta?.name : '') || tab.account?.host || tab.label?.split(' · ')[0] || '').trim()
  const clean = rawHost.replace(/\s*\([^)]*\)\s*$/, '').trim()
  if (!clean) return 'SSH'
  if (/^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(clean)) {
    const parts = clean.split('.')
    return `.${parts[parts.length - 1]}`
  }
  if (/^[a-zA-Z0-9_\-]+$/.test(clean)) {
    return clean.length <= 4 ? clean : clean.slice(0, 4)
  }
  return clean.length <= 3 ? clean : clean.slice(0, 2)
}

export const MINI_DOCK_PALETTES = [
  { color: '#8b5cf6', border: 'rgba(139, 92, 246, 0.45)', bg: 'rgba(139, 92, 246, 0.12)' }, // Purple
  { color: '#06b6d4', border: 'rgba(6, 182, 212, 0.45)', bg: 'rgba(6, 182, 212, 0.12)' },  // Cyan
  { color: '#3b82f6', border: 'rgba(59, 130, 246, 0.45)', bg: 'rgba(59, 130, 246, 0.12)' },  // Blue
  { color: '#f59e0b', border: 'rgba(245, 158, 11, 0.45)', bg: 'rgba(245, 158, 11, 0.12)' },  // Amber
  { color: '#10b981', border: 'rgba(16, 185, 129, 0.45)', bg: 'rgba(16, 185, 129, 0.12)' }, // Emerald
  { color: '#f43f5e', border: 'rgba(244, 63, 94, 0.45)', bg: 'rgba(244, 63, 94, 0.12)' },   // Rose
  { color: '#14b8a6', border: 'rgba(20, 184, 166, 0.45)', bg: 'rgba(20, 184, 166, 0.12)' }   // Teal
]

export function getTabThemeStyle(tab) {
  if (!tab) return {}
  const seed = Number(tab.hostId) || (tab.tabId ? String(tab.tabId).split('').reduce((acc, c) => acc + c.charCodeAt(0), 0) : 0)
  const theme = MINI_DOCK_PALETTES[Math.abs(seed) % MINI_DOCK_PALETTES.length]
  return {
    '--node-theme-color': theme.color,
    '--node-theme-border': theme.border,
    '--node-theme-bg': theme.bg
  }
}
