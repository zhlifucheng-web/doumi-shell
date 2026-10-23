export function tmuxCompatibility(connectionMode = '', version = '', source = 'unknown') {
  const tmux = connectionMode.includes('tmux')
  if (!tmux) return { tmux: false, label: connectionMode || 'SSH', features: [], warning: false, historySupported: false }
  const parsed = /^(next-)?(\d+)\.(\d+)([a-z]|-rc\d+)?$/.exec(version)
  const known = Boolean(parsed && !parsed[1])
  const atLeast = (major, minor) => {
    if (!known) return null
    const actualMajor = Number(parsed[2]), actualMinor = Number(parsed[3])
    if (actualMajor < major || (actualMajor === major && actualMinor < minor)) return false
    if (actualMajor === major && actualMinor === minor && parsed[4]?.startsWith('-rc')) return false
    return source === 'server' ? true : null
  }
  const features = [
    { key: 'session', name: '会话保持与共享', minimum: '1.6', available: atLeast(1, 6) },
    { key: 'mouse', name: '鼠标滚动（自动适配）', minimum: '1.6', available: atLeast(1, 6) },
    { key: 'history', name: '历史内容搜索', minimum: '1.8', available: atLeast(1, 8) }
  ].map(feature => ({ ...feature, status: feature.available === null ? '待确认' : feature.available ? '支持' : '不支持' }))
  const historySupported = features.find(feature => feature.key === 'history').available
  const displayVersion = parsed ? version : '版本未知'
  const label = connectionMode.replace('tmux', `tmux ${displayVersion}`)
  const note = source === 'server' && known
    ? '已识别当前运行的 tmux 服务端版本。功能仍受远端配置与权限影响。'
    : source === 'executable' && known
      ? '仅识别到远端可执行文件版本，运行中的服务端版本未确认；功能以实际执行结果为准。'
      : '未能确认运行中的 tmux 版本；可尝试历史读取，功能以实际执行结果为准。'
  const historyNote = historySupported === false
    ? `tmux ${displayVersion} 不满足历史搜索要求（需 1.8+），请使用本次连接搜索、tmux 复制模式，或升级 tmux。`
    : historySupported === null ? note : ''
  return { tmux, label, version: displayVersion, source, features, historySupported, historyNote, note,
    warning: features.some(feature => feature.available !== true) }
}
