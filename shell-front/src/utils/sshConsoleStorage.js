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
