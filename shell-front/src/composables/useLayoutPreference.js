import { ref, watch } from 'vue'

const preferences = {
  sidebarWidth: { key: 'doumi-sidebar-width', initial: 280, min: 220, max: 640 },
  sidebarCollapsed: { key: 'doumi-sidebar-collapsed', initial: false },
  monitorWidth: { key: 'doumi-monitor-width-v2', initial: 380, min: 260, max: 760 },
  monitorExpanded: { key: 'doumi-monitor-expanded', initial: false },
  terminalPercent: { key: 'doumi-terminal-height-percent', initial: 60, min: 30, max: 75 },
  filesCollapsed: { key: 'doumi-terminal-files-collapsed', initial: false }
}

function browserStorage() {
  try { return typeof localStorage === 'undefined' ? null : localStorage }
  catch { return null }
}

export function useLayoutPreference(name, storage = browserStorage()) {
  const { key, initial, min, max } = preferences[name]
  const valid = value => typeof initial === 'boolean'
    ? typeof value === 'boolean'
    : Number.isFinite(value) && value >= min && value <= max
  const value = ref(initial)
  try {
    const raw = storage?.getItem(key)
    if (raw != null && raw.trim() !== '') {
      const saved = typeof initial === 'boolean'
        ? (raw === 'true' ? true : raw === 'false' ? false : null)
        : Number(raw)
      if (valid(saved)) value.value = saved
    }
  } catch { /* Keep defaults when browser storage is unavailable. */ }
  watch(value, next => {
    if (!valid(next)) return
    try { storage?.setItem(key, String(next)) }
    catch { /* Layout remains usable when storage is blocked or full. */ }
  }, { flush: 'sync' })
  return value
}
