import { del, get, patch, post, put, apiClient, API_BASE, handleApiResponse } from '@/utils/apiHandler'

const BASE = '/api/ssh-console'

/** 与 HTTP 共用同源地址；支持显式 VITE_API_BASE。 */
export function buildTerminalWsUrl(accountId, { cols, rows } = {}) {
  const params = new URLSearchParams({ accountId: String(accountId) })
  if (cols > 0) params.set('cols', String(cols))
  if (rows > 0) params.set('rows', String(rows))
  const url = new URL(`${API_BASE}/ws/ssh-console/terminal`, window.location.origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.search = params.toString()
  return url.toString()
}

export function listScheduleAccounts() {
  return get(`${BASE}/scripts/schedule-accounts`, {}, { showSuccess: false })
}

export function previewCron(cron, zone) {
  return post(`${BASE}/scripts/cron-preview`, { cron, zone }, { showSuccess: false })
}

export function exportConfiguration(password, encrypted = true) {
  return post(`${BASE}/configuration/export`, { password, encrypted }, { showSuccess: false })
}

export function importConfiguration(password, archive) {
  return post(`${BASE}/configuration/import`, { password, archive }, { showSuccess: false })
}

export function fetchTreeChildren(parentType, parentId) {
  const params = { parentType }
  if (parentId != null) {
    params.parentId = parentId
  }
  return get(`${BASE}/tree/children`, params, { showSuccess: false })
}

export function moveTreeNode(body) {
  return patch(`${BASE}/tree/reorder`, body)
}

export function getGroup(id) {
  return get(`${BASE}/groups/${id}`, {}, { showSuccess: false })
}

export function createGroup(body) {
  return post(`${BASE}/groups`, body)
}

export function updateGroup(id, body) {
  return put(`${BASE}/groups/${id}`, body)
}

export function deleteGroup(id) {
  return del(`${BASE}/groups/${id}`)
}

export function moveGroup(id, parentId) {
  return patch(`${BASE}/groups/${id}/move`, { parentId })
}

export function getHost(id) {
  return get(`${BASE}/hosts/${id}`, {}, { showSuccess: false })
}

export function createHost(body) {
  return post(`${BASE}/hosts`, body)
}

export function updateHost(id, body) {
  return put(`${BASE}/hosts/${id}`, body)
}

export function deleteHost(id) {
  return del(`${BASE}/hosts/${id}`)
}

export function moveHost(id, groupId) {
  return patch(`${BASE}/hosts/${id}/move`, { groupId })
}

export function getAccount(id) {
  return get(`${BASE}/accounts/${id}`, {}, { showSuccess: false })
}

export function createAccount(body) {
  return post(`${BASE}/accounts`, body)
}

export function updateAccount(id, body) {
  return put(`${BASE}/accounts/${id}`, body)
}

export function deleteAccount(id) {
  return del(`${BASE}/accounts/${id}`)
}

export function testAccount(id) {
  return post(`${BASE}/accounts/${id}/test`, {}, { showSuccess: false })
}

export function openAccountSession(id) {
  return post(`${BASE}/accounts/${id}/session`, {}, { showSuccess: false })
}

export function sftpList(sessionToken, path = '') {
  const params = { sessionToken }
  if (path) params.path = path
  return get(`${BASE}/sftp/list`, params, { showSuccess: false })
}

export function sftpMkdir(sessionToken, path) {
  return post(`${BASE}/sftp/mkdir`, { sessionToken, path }, { showSuccess: false })
}

export function sftpDelete(sessionToken, path) {
  return post(`${BASE}/sftp/delete`, { sessionToken, path })
}

export function sftpRename(sessionToken, path, newName) {
  return post(`${BASE}/sftp/rename`, { sessionToken, path, newName })
}

export function killHostProcess(sessionToken, pid, signal = 'TERM') {
  return post(`${BASE}/host/processes/kill`, { sessionToken, pid, signal }, { showSuccess: false })
}

export function sftpUpload(sessionToken, path, file) {
  const form = new FormData()
  form.append('file', file)
  const q = new URLSearchParams({ sessionToken })
  if (path) q.set('path', path)
  return post(`${BASE}/sftp/upload?${q.toString()}`, form, { showSuccess: false })
}

export async function sftpUploadLarge(sessionToken, path, file, { signal, onProgress, onCommitting } = {}) {
  const chunkSize = 4 * 1024 * 1024
  let uploadId
  const request = (action, params, body = null, requestSignal = signal, timeout = 0) => handleApiResponse(
    apiClient.post(`${BASE}/sftp/upload/${action}`, body, {
      params: { sessionToken, ...params }, timeout, signal: requestSignal
    }), { showSuccess: false, showFailure: false })
  try {
    signal?.throwIfAborted()
    uploadId = await request('start', { path, name: file.name, total: file.size })
    for (let offset = 0; offset < file.size; offset += chunkSize) {
      signal?.throwIfAborted()
      const end = Math.min(file.size, offset + chunkSize)
      const form = new FormData()
      form.append('file', file.slice(offset, end), file.name)
      await request('chunk', { uploadId, offset }, form)
      onProgress?.(end, file.size)
    }
    signal?.throwIfAborted()
    // Final rename is a commit: do not interrupt it and report a successful replacement as cancelled.
    onCommitting?.()
    await request('complete', { uploadId }, null, null)
  } catch (error) {
    if (uploadId) {
      try { await request('cancel', { uploadId }, null, null, 15000) }
      catch { error.cleanupWarning = '临时文件清理未确认，服务端将重试；连接断开时可能残留 .doumi-upload-*.part' }
    }
    throw error
  }
}

export async function sftpDownloadLarge(sessionToken, path, { handle, signal, onProgress, onCommitting } = {}) {
  const url = new URL(`${API_BASE}${BASE}/sftp/download`, window.location.origin)
  url.search = new URLSearchParams({ sessionToken, path }).toString()
  if (!handle) {
    // Native downloads stream to disk without retaining a Blob in JavaScript memory.
    const link = document.createElement('a')
    link.href = url.href
    link.download = ''
    link.target = '_blank'
    link.rel = 'noopener'
    link.click()
    return 'browser'
  }
  signal?.throwIfAborted()
  const response = await fetch(url, { signal })
  if (!response.ok || response.headers.get('content-type')?.includes('json')) {
    let message = '下载失败'
    try { const error = await response.json(); message = error.externMessage || error.message || message } catch { /* Invalid error body. */ }
    throw new Error(message)
  }
  const total = Number(response.headers.get('content-length')) || 0
  const reader = response.body?.getReader()
  if (!reader) throw new Error('浏览器无法读取下载流')
  let writable, loaded = 0
  try {
    writable = await handle.createWritable()
    while (true) {
      signal?.throwIfAborted()
      const { done, value } = await reader.read()
      if (done) break
      await writable.write(value)
      loaded += value.byteLength
      onProgress?.(loaded, total)
    }
    signal?.throwIfAborted()
    if (total && loaded !== total) throw new Error('下载中断，文件大小不完整')
    onCommitting?.()
    await writable.close()
    return 'done'
  } catch (error) {
    try { await writable?.abort() } catch { /* Preserve original error. */ }
    throw error
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

/** 下载为浏览器保存（@NoWrap 二进制响应） */
export function fetchHostMetrics(sessionToken, { signal } = {}) {
  return handleApiResponse(apiClient.get(`${BASE}/host/metrics`, {
    params: { sessionToken }, signal, timeout: 30000
  }), { showSuccess: false, showFailure: false })
}

export function fetchHostProcesses(sessionToken) {
  return get(`${BASE}/host/processes`, { sessionToken }, { showSuccess: false })
}

export function scanDiskUsage(sessionToken, path, minimumMb) {
  return handleApiResponse(apiClient.get(`${BASE}/host/disk-usage`, {
    params: { sessionToken, path, minimumMb }, timeout: 105000
  }), { showSuccess: false })
}

export function listRemoteDirectories(sessionToken, path) {
  return get(`${BASE}/sftp/directories`, { sessionToken, path }, { showSuccess: false, showFailure: false })
}

export function listScripts(hostId) {
  const params = {}
  if (hostId != null) params.hostId = hostId
  return get(`${BASE}/scripts`, params, { showSuccess: false })
}

export function getScript(id) {
  return get(`${BASE}/scripts/${id}`, {}, { showSuccess: false })
}

export function createScript(body) {
  return post(`${BASE}/scripts`, body)
}

export function updateScript(id, body) {
  return put(`${BASE}/scripts/${id}`, body)
}

export function deleteScript(id) {
  return del(`${BASE}/scripts/${id}`)
}

export function runScript(id, sessionToken) {
  return post(`${BASE}/scripts/${id}/run`, { sessionToken }, { showSuccess: false })
}

/**
 * 流式执行脚本（NDJSON：meta / stdout / stderr / done / error）
 */
export async function runScriptStream(id, sessionToken, handlers = {}) {
  const { signal, onMeta, onStdout, onStderr, onDone, onError } = handlers
  const url = `${API_BASE}${BASE}/scripts/${id}/run/stream`
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionToken }),
    signal
  })
  const ct = resp.headers.get('content-type') || ''
  if (!resp.ok || ct.includes('application/json')) {
    const text = await resp.text()
    let msg = `执行失败 (${resp.status})`
    try {
      const j = JSON.parse(text)
      msg = j.externMessage || j.message || msg
    } catch {
      if (text) msg = text.slice(0, 500)
    }
    throw new Error(msg)
  }
  const reader = resp.body?.getReader()
  if (!reader) {
    throw new Error('无法读取响应流')
  }
  const decoder = new TextDecoder()
  let buf = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).trim()
      buf = buf.slice(idx + 1)
      if (!line) continue
      const ev = JSON.parse(line)
      if (ev.type === 'meta' && onMeta) onMeta(ev)
      else if (ev.type === 'stdout' && onStdout) onStdout(ev.text || '')
      else if (ev.type === 'stderr' && onStderr) onStderr(ev.text || '')
      else if (ev.type === 'done' && onDone) onDone(ev)
      else if (ev.type === 'error' && onError) onError(ev.message || '执行失败')
    }
  }
  if (buf.trim()) {
    try {
      const ev = JSON.parse(buf.trim())
      if (ev.type === 'done' && onDone) onDone(ev)
    } catch {
      /* ignore partial */
    }
  }
}

export const sftpFolderTree = (sessionToken, path) => handleApiResponse(apiClient.get(`${BASE}/sftp/folder/tree`, {params:{sessionToken,path},timeout:120000}), {showSuccess:false})
export const sftpFolderLimit = () => get(`${BASE}/sftp/folder/limits`, {}, {showSuccess:false})
export const sftpFolderMkdir = (sessionToken,path,relativePath) => handleApiResponse(apiClient.post(`${BASE}/sftp/folder/mkdir`,null,{params:{sessionToken,path,relativePath}}),{showSuccess:false})
export function sftpFolderUpload(sessionToken,path,file,{archive=false,relativePath=''}={}) {
  const form=new FormData();form.append('file',file)
  return handleApiResponse(apiClient.post(`${BASE}/sftp/folder/upload`,form,{params:{sessionToken,path,archive,relativePath},timeout:0}),{showSuccess:false})
}
export async function sftpBlob(sessionToken,path,{folder=false,compress=true}={}) {
  const url = `${API_BASE}${BASE}/sftp/${folder?'folder/download':'download'}`
  const resp = await apiClient.get(url, {
    params: { sessionToken, path, compress },
    responseType: 'blob', timeout:0
  })
  const blob = resp.data
  const ct = resp.headers?.['content-type'] || ''
  if (ct.includes('application/json') || (blob.type && blob.type.includes('json'))) {
    const text = await blob.text()
    let msg = '下载失败'
    try {
      const j = JSON.parse(text)
      msg = j.message || j.externMessage || msg
    } catch {
      /* ignore */
    }
    throw new Error(msg)
  }
  return blob
}
export async function sftpDownload(sessionToken, path, filename, options={}) {
  const blob=await sftpBlob(sessionToken,path,options)
  const name = filename || 'download.bin'
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = name
  link.click()
  setTimeout(()=>URL.revokeObjectURL(link.href),10000)
}

export const startExecution = (scriptId, accountId) => post(`${BASE}/executions`, {scriptId,accountId}, {showSuccess:false})
export const listExecutions = params => get(`${BASE}/executions`, params, {showSuccess:false})
export const getExecution = id => get(`${BASE}/executions/${id}`, {}, {showSuccess:false})
export const stopExecution = id => post(`${BASE}/executions/${id}/stop`, {}, {showSuccess:false})
export const cleanupTables = () => get(`${BASE}/data-cleanup/tables`, {}, {showSuccess:false})
export const previewCleanup = body => post(`${BASE}/data-cleanup/preview`, body, {showSuccess:false})
export const clearData = token => post(`${BASE}/data-cleanup/clear`, {token}, {showSuccess:false})
