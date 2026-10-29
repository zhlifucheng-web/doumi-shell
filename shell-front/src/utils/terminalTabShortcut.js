export const isMacPlatform = typeof navigator !== 'undefined' && /Mac|iPod|iPhone|iPad/i.test(navigator.platform || navigator.userAgent || '')

export function getTabShortcutLabel(index) {
  if (isMacPlatform) {
    return `Ctrl+${index} 或 ⌘⌥${index}`
  }
  return `Ctrl+${index} 或 Alt+${index}`
}

/**
 * 解析终端标签切换快捷键
 * 全兼容支持:
 * - Cmd + 1~9 (Mac 独立窗口/PWA 模式)
 * - Cmd + Option + 1~9 (Mac 普通浏览器标签页推荐，不被浏览器抢占)
 * - Ctrl + 1~9 (通用/Linux/Windows 终端标准)
 * - Alt + 1~9 (Windows/Linux 避开浏览器拦截方案)
 * - 数字 0 切换到最后一个标签
 * - PageUp / PageDown (相对轮转上一个/下一个)
 */
export function parseTabSwitchShortcut(event) {
  if (!event) return null
  if (event.shiftKey) return null

  const isCtrl = event.ctrlKey && !event.metaKey && !event.altKey
  const isCmd = event.metaKey && !event.ctrlKey && !event.altKey
  const isCmdOption = event.metaKey && event.altKey && !event.ctrlKey
  const isAlt = event.altKey && !event.metaKey && !event.ctrlKey

  if (!isCtrl && !isCmd && !isCmdOption && !isAlt) return null

  const code = event.code || ''
  const key = event.key || ''

  // Digit 1 - 9
  const digitMatch = code.match(/^(?:Digit|Numpad)([1-9])$/)
  if (digitMatch) {
    return { type: 'index', index: parseInt(digitMatch[1], 10) }
  }
  if (key >= '1' && key <= '9') {
    return { type: 'index', index: parseInt(key, 10) }
  }

  // Digit 0 (切换到最后一个标签)
  if (code === 'Digit0' || code === 'Numpad0' || key === '0') {
    return { type: 'last' }
  }

  // PageUp / PageDown 循环切换
  if (code === 'PageUp' || key === 'PageUp') {
    return { type: 'prev' }
  }
  if (code === 'PageDown' || key === 'PageDown') {
    return { type: 'next' }
  }

  return null
}

/**
 * 根据解析出的动作和当前标签列表计算目标标签
 * @param {Array} tabs 终端标签列表
 * @param {string|number} activeId 当前激活的 tabId
 * @param {Object} action 快捷键动作
 * @returns {Object|null} 目标标签对象
 */
export function resolveTargetTab(tabs, activeId, action) {
  if (!Array.isArray(tabs) || tabs.length === 0 || !action) return null
  const len = tabs.length
  const currentIndex = tabs.findIndex(t => t.tabId === activeId)

  if (action.type === 'index') {
    const targetIdx = action.index - 1
    if (targetIdx >= 0 && targetIdx < len) {
      return tabs[targetIdx]
    }
    return null
  }

  if (action.type === 'last') {
    return tabs[len - 1]
  }

  if (action.type === 'next') {
    if (currentIndex < 0) return tabs[0]
    const nextIdx = (currentIndex + 1) % len
    return tabs[nextIdx]
  }

  if (action.type === 'prev') {
    if (currentIndex < 0) return tabs[0]
    const prevIdx = (currentIndex - 1 + len) % len
    return tabs[prevIdx]
  }

  return null
}
