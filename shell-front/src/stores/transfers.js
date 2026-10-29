import { reactive, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { isTransferActive, readTransferHistory, saveTransferHistory, removeTransferHistory, trimTransferHistory, transferErrorMessage, saveTransferHost } from '../composables/transferHistory.js'

export const useTransferStore = defineStore('transfers', () => {
  const transfers = reactive([])
  const transferHistoryWarning = ref('')
  const queue = []
  const tracking = new Map()
  const sessionIds = new Map()
  let storage
  let initialized = false
  let running = false

  function initialize() {
    if (initialized) return
    initialized = true
    try {
      if (typeof window !== 'undefined') {
        storage = window.localStorage
        trimTransferHistory(storage)
        transfers.push(...readTransferHistory(storage))
      }
    } catch { transferHistoryWarning.value = '浏览器禁止或无法读取本地存储，刷新或关闭页面后将无法查看本次任务记录。' }
    if (typeof window !== 'undefined') {
      const persistAll = () => { for (const item of tracking.values()) item.persist() }
      window.addEventListener('pagehide', persistAll)
      window.addEventListener('beforeunload', event => {
        persistAll()
        if (transfers.some(task => !task.restored && isTransferActive(task))) { event.preventDefault(); event.returnValue = '' }
      })
    }
  }

  function track(task) {
    if (!storage) return
    let timer
    const persist = () => {
      clearTimeout(timer); timer = undefined; task.updatedAt = Date.now()
      try {
        saveTransferHistory(storage, task)
        if (storage.length > 200) trimTransferHistory(storage)
        transferHistoryWarning.value = ''
      } catch { transferHistoryWarning.value = '任务记录保存失败（本地存储被禁用或空间不足），请勿依赖关闭页面后恢复记录。' }
    }
    persist()
    const stopStatus = watch(() => task.status, persist, { flush: 'sync' })
    const stopError = watch(() => task.error, persist, { flush: 'sync' })
    const stopProgress = watch(() => [task.loaded, task.total], () => {
      if (!timer) timer = setTimeout(() => { timer = undefined; persist() }, 500)
    }, { flush: 'sync' })
    tracking.set(task.id, { persist, stop: () => { clearTimeout(timer); stopStatus(); stopError(); stopProgress() } })
    try { trimTransferHistory(storage) } catch { /* Persistence errors are reported by persist. */ }
  }

  function transferSessionId(accountId, token) {
    if (accountId == null || !token) return ''
    let sessions = sessionIds.get(accountId)
    if (!sessions) { sessions = new Map(); sessionIds.set(accountId, sessions) }
    if (!sessions.has(token)) sessions.set(token, globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`)
    return sessions.get(token)
  }
  function assignTransferHost(accountId, hostId) {
    initialize()
    if (accountId == null || hostId == null) return
    for (const task of transfers) {
      if (task.accountId !== accountId || task.hostId != null) continue
      task.hostId = hostId
      if (storage) try { saveTransferHost(storage, task) } catch { transferHistoryWarning.value = '服务器归属保存失败，重新打开后需要再次选择对应账户。' }
    }
  }
  function enqueueTransfer({ name, direction, path, total, accountId, hostId, sessionId, run }) {
    initialize()
    const controller = new AbortController()
    const task = reactive({ id: `${Date.now()}-${Math.random().toString(36).slice(2)}`, createdAt: Date.now(), name, direction, path, total, accountId, hostId, sessionId, loaded: 0,
      status: '等待中', error: '', speed: 0, cancel: () => {
        if (!['等待中', '传输中'].includes(task.status)) return
        controller.abort()
        const index = queue.indexOf(work)
        if (index !== -1) { queue.splice(index, 1); task.status = '已取消'; run = null; task.cancel = () => {} }
        else task.status = '取消中'
      } })
    transfers.push(task); track(task)
    const work = async () => {
      if (controller.signal.aborted) { task.status = '已取消'; return }
      task.status = '传输中'; const start = performance.now()
      try {
        const result = await run({ signal: controller.signal, onCommitting: () => { task.status = '保存中' }, onProgress: (loaded, currentTotal) => {
          task.loaded = loaded; task.total = currentTotal || task.total; task.speed = loaded / Math.max((performance.now() - start) / 1000, 0.001)
        } })
        task.status = controller.signal.aborted && !result?.completed ? '已取消' : '已完成'
        if (result?.warning) task.error = result.warning
      } catch (error) {
        const committing = task.status === '保存中'
        task.status = controller.signal.aborted || error.name === 'AbortError' ? '已取消' : committing ? '结果待确认' : '失败'
        task.error = [task.status !== '已取消' ? transferErrorMessage(error, direction) : '', committing ? '提交结果未确认，请检查远程目标或本机文件后再重试。' : '', error.cleanupWarning].filter(Boolean).join('；')
      } finally { run = null; task.cancel = () => {} }
    }
    queue.push(work); void drain(); return task
  }
  async function delegateBrowserDownload({ name, path, total, accountId, hostId, sessionId, run }) {
    initialize()
    const task = reactive({ id: `${Date.now()}-${Math.random().toString(36).slice(2)}`, createdAt: Date.now(), name, direction: '下载', path, total, accountId, hostId, sessionId, loaded: 0, status: '交给浏览器中', error: '', speed: 0 })
    transfers.push(task); track(task)
    try { await run(); task.status = '已交给浏览器' } catch (error) { task.status = '失败'; task.error = error.message || '无法启动下载' }
  }
  async function drain() {
    if (running) return
    running = true
    try { while (queue.length) await queue.shift()() } finally { running = false }
  }
  function clearFinishedTransfers(matches = () => true) {
    initialize()
    for (let index = transfers.length - 1; index >= 0; index--) {
      const task = transfers[index]
      if (!matches(task) || isTransferActive(task)) continue
      tracking.get(task.id)?.stop(); tracking.delete(task.id)
      try { if (storage) removeTransferHistory(storage, task) } catch { transferHistoryWarning.value = '本地历史删除失败，重新打开页面后记录可能再次出现，请检查浏览器存储权限。' }
      transfers.splice(index, 1)
    }
  }

  return { transfers, transferHistoryWarning, initialize, transferSessionId, assignTransferHost, enqueueTransfer, delegateBrowserDownload, clearFinishedTransfers }
})
