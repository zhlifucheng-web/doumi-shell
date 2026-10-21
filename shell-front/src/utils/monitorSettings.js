const PREFIX = 'doumi-monitor-'

// Keep monitor preferences separate from runtime history and connection state.
export function exportMonitorSettings(storage = window.localStorage) {
  const entries = {}
  try {
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index)
      if (!key || !key.startsWith(PREFIX)) continue
      entries[key] = storage.getItem(key)
    }
  } catch {
    return { version: 1, entries: {} }
  }
  return { version: 1, entries }
}

export function importMonitorSettings(settings, storage = window.localStorage) {
  if (!settings || settings.version !== 1 || !settings.entries || typeof settings.entries !== 'object') return 0
  let count = 0
  Object.entries(settings.entries).forEach(([key, value]) => {
    if (!key.startsWith(PREFIX) || typeof value !== 'string' || value.length > 1024 * 1024) return
    try {
      storage.setItem(key, value)
      count += 1
    } catch { /* A restricted browser storage must not abort configuration import. */ }
  })
  return count
}
