export function formatSize(n) {
  const v = Number(n) || 0
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  if (v < 1024 * 1024 * 1024) return `${(v / 1024 / 1024).toFixed(1)} MB`
  return `${(v / 1024 / 1024 / 1024).toFixed(2)} GB`
}

export function formatSpeed(bytesPerSec) {
  const speed = Number(bytesPerSec) || 0
  if (speed <= 0) return '0 B/s'
  return `${formatSize(speed)}/s`
}

export function formatEta(seconds) {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds) || seconds < 0) {
    return '计算中...'
  }
  const s = Math.round(seconds)
  if (s <= 0) return '即将完成'
  if (s < 60) return `${s} 秒`
  if (s < 3600) {
    const mins = Math.floor(s / 60)
    const rem = s % 60
    return rem > 0 ? `${mins} 分 ${rem} 秒` : `${mins} 分钟`
  }
  const hours = Math.floor(s / 3600)
  const remMins = Math.floor((s % 3600) / 60)
  return remMins > 0 ? `${hours} 小时 ${remMins} 分` : `${hours} 小时`
}

export function getTaskEta(task) {
  if (!task) return '--'
  if (task.status === '等待中') return '排队等待中'
  if (task.status === '保存中') return '正在写入远程'
  if (task.status === '取消中') return '取消中'
  if (task.status === '已完成') return '已完成'
  if (task.status !== '传输中') return task.status || '--'

  const speed = Math.max(0, Number(task.speed) || 0)
  const total = Math.max(0, Number(task.total) || 0)
  const loaded = Math.max(0, Number(task.loaded) || 0)
  const remaining = Math.max(0, total - loaded)

  if (remaining === 0 && total > 0) return '即将完成'
  if (speed <= 0) return '计算中...'
  return `剩余 ${formatEta(remaining / speed)}`
}

export function getOverallEta(overallProgress, totalSpeed) {
  const total = Math.max(0, Number(overallProgress?.total) || 0)
  const loaded = Math.max(0, Number(overallProgress?.loaded) || 0)
  const remaining = Math.max(0, total - loaded)
  const speed = Math.max(0, Number(totalSpeed) || 0)

  if (remaining === 0 && total > 0) return '即将完成'
  if (speed <= 0) return '计算中...'
  return `剩余 ${formatEta(remaining / speed)}`
}
