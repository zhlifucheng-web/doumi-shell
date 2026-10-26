/**
 * SFTP / File panel sorting utility
 * 
 * Rules:
 * 1. Directories (dir === true) ALWAYS remain grouped at the top in both ascending and descending sorts.
 * 2. Natural alphanumeric sorting for names with Chinese collation support (e.g. file2 before file10).
 * 3. Fallback for createTime: item.createTime ?? item.atime ?? item.mtime ?? 0.
 * 4. Stable tie-breaker: sort by filename ascending.
 */

export function normalizeOrder(order) {
  if (!order) return 'ascending'
  const lower = String(order).toLowerCase()
  if (lower === 'desc' || lower === 'descending') return 'descending'
  return 'ascending'
}

export function sortFileList(items, sortConfig = {}) {
  if (!Array.isArray(items) || items.length <= 1) {
    return Array.isArray(items) ? [...items] : []
  }

  const prop = sortConfig.prop || 'name'
  const order = normalizeOrder(sortConfig.order)
  const isDesc = order === 'descending'

  return [...items].sort((a, b) => {
    // Rule 1: Directories always stay at top
    const aIsDir = Boolean(a?.dir)
    const bIsDir = Boolean(b?.dir)
    if (aIsDir !== bIsDir) {
      return aIsDir ? -1 : 1
    }

    let cmp = 0
    switch (prop) {
      case 'name': {
        const aName = String(a?.name || '')
        const bName = String(b?.name || '')
        cmp = aName.localeCompare(bName, 'zh-CN', { numeric: true, sensitivity: 'base' })
        break
      }
      case 'size': {
        const aSize = Number(a?.size) || 0
        const bSize = Number(b?.size) || 0
        cmp = aSize - bSize
        break
      }
      case 'createTime': {
        const aTime = Number(a?.createTime ?? a?.atime ?? a?.mtime) || 0
        const bTime = Number(b?.createTime ?? b?.atime ?? b?.mtime) || 0
        cmp = aTime - bTime
        break
      }
      case 'mtime': {
        const aTime = Number(a?.mtime) || 0
        const bTime = Number(b?.mtime) || 0
        cmp = aTime - bTime
        break
      }
      case 'owner': {
        const aOwner = String(a?.owner || '')
        const bOwner = String(b?.owner || '')
        cmp = aOwner.localeCompare(bOwner, 'zh-CN', { numeric: true })
        break
      }
      case 'group': {
        const aGroup = String(a?.group || '')
        const bGroup = String(b?.group || '')
        cmp = aGroup.localeCompare(bGroup, 'zh-CN', { numeric: true })
        break
      }
      case 'permissions': {
        const aPerm = String(a?.permissions || '')
        const bPerm = String(b?.permissions || '')
        cmp = aPerm.localeCompare(bPerm)
        break
      }
      default: {
        const aVal = a?.[prop]
        const bVal = b?.[prop]
        if (typeof aVal === 'number' && typeof bVal === 'number') {
          cmp = aVal - bVal
        } else {
          cmp = String(aVal || '').localeCompare(String(bVal || ''), 'zh-CN', { numeric: true })
        }
        break
      }
    }

    if (isDesc) {
      cmp = -cmp
    }

    // Tie-breaker: stable natural sort by name ascending
    if (cmp === 0) {
      const aName = String(a?.name || '')
      const bName = String(b?.name || '')
      cmp = aName.localeCompare(bName, 'zh-CN', { numeric: true, sensitivity: 'base' })
    }

    return cmp
  })
}
