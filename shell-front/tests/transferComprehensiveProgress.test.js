import test from 'node:test'
import assert from 'node:assert/strict'

function computeOverallProgress(tasks, batchStart = null) {
  const ACTIVE_STATUSES = new Set(['等待中', '传输中', '取消中', '保存中', '交给浏览器中'])
  const isActive = t => ACTIVE_STATUSES.has(t.status)

  const activeTasks = tasks.filter(isActive)
  if (activeTasks.length === 0) {
    return {
      activeCount: 0,
      loaded: 0,
      total: 0,
      percent: 0,
      label: `传输列表（${tasks.length}）`
    }
  }

  const batchTasks = tasks.filter(task => {
    if (isActive(task)) return true
    if (batchStart !== null && task.status === '已完成') {
      const time = task.updatedAt || task.createdAt || 0
      return time >= batchStart
    }
    return false
  })

  let totalLoaded = 0
  let totalSize = 0
  for (const t of batchTasks) {
    const tTotal = Math.max(0, Number(t.total) || 0)
    const tLoaded = t.status === '已完成'
      ? tTotal
      : Math.min(tTotal > 0 ? tTotal : Infinity, Math.max(0, Number(t.loaded) || 0))
    totalLoaded += tLoaded
    totalSize += tTotal
  }

  const percent = totalSize > 0
    ? Math.min(99, Math.floor((totalLoaded / totalSize) * 100))
    : 0

  const percentPart = totalSize > 0 ? ` · ${percent}%` : ''
  return {
    activeCount: activeTasks.length,
    loaded: totalLoaded,
    total: totalSize,
    percent,
    label: `传输列表（${activeTasks.length} 项进行中${percentPart}）`
  }
}

test('no active transfers shows total record count', () => {
  const tasks = [
    { id: '1', status: '已完成', total: 100, loaded: 100 },
    { id: '2', status: '已取消', total: 50, loaded: 10 }
  ]
  const progress = computeOverallProgress(tasks)
  assert.equal(progress.activeCount, 0)
  assert.equal(progress.percent, 0)
  assert.equal(progress.label, '传输列表（2）')
})

test('single active transfer calculates and formats overall percentage', () => {
  const tasks = [
    { id: '1', status: '传输中', total: 1000, loaded: 450 }
  ]
  const progress = computeOverallProgress(tasks)
  assert.equal(progress.activeCount, 1)
  assert.equal(progress.percent, 45)
  assert.equal(progress.label, '传输列表（1 项进行中 · 45%）')
})

test('multiple active transfers compute combined percentage across queue', () => {
  const tasks = [
    { id: '1', status: '传输中', total: 500, loaded: 250 },
    { id: '2', status: '等待中', total: 500, loaded: 0 }
  ]
  // Total size = 1000, total loaded = 250 -> 25%
  const progress = computeOverallProgress(tasks)
  assert.equal(progress.activeCount, 2)
  assert.equal(progress.loaded, 250)
  assert.equal(progress.total, 1000)
  assert.equal(progress.percent, 25)
  assert.equal(progress.label, '传输列表（2 项进行中 · 25%）')
})

test('completed task in current batch preserves progress and avoids progress dropping to zero', () => {
  const now = Date.now()
  const batchStart = now - 5000

  // File 1 finished (500/500), File 2 halfway (250/500)
  const tasks = [
    { id: '1', status: '已完成', total: 500, loaded: 500, createdAt: now - 4000, updatedAt: now - 2000 },
    { id: '2', status: '传输中', total: 500, loaded: 250, createdAt: now - 4000, updatedAt: now - 1000 }
  ]
  // Total size = 1000, total loaded = 500 + 250 = 750 -> 75%
  const progress = computeOverallProgress(tasks, batchStart)
  assert.equal(progress.activeCount, 1)
  assert.equal(progress.loaded, 750)
  assert.equal(progress.total, 1000)
  assert.equal(progress.percent, 75)
  assert.equal(progress.label, '传输列表（1 项进行中 · 75%）')
})

test('zero total size produces safe 0% without NaN', () => {
  const tasks = [
    { id: '1', status: '等待中', total: 0, loaded: 0 }
  ]
  const progress = computeOverallProgress(tasks)
  assert.equal(progress.activeCount, 1)
  assert.equal(progress.percent, 0)
  assert.equal(progress.label, '传输列表（1 项进行中）')
})
