import { getMonitorConfig, saveMonitorConfig } from '@/api/sshConsole'

const EVENT_NAME = 'doumi-monitor-config-changed'
const channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('doumi-monitor-config-v1')

function publish(detail) {
  window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail }))
}

if (channel) channel.addEventListener('message', event => publish(event.data || {}))

export function loadMonitorConfig(accountId) {
  return getMonitorConfig(accountId)
}

export async function saveAccountMonitorConfig(accountId, config, sourceId = null) {
  const saved = await saveMonitorConfig(accountId, config)
  const detail = { accountId, revision: saved?.revision, sourceId }
  publish(detail)
  if (channel) channel.postMessage(detail)
  return saved
}

export function notifyMonitorConfigChanged(accountId, revision = null) {
  const detail = { accountId, revision }
  publish(detail)
  if (channel) channel.postMessage(detail)
}

export { EVENT_NAME as MONITOR_CONFIG_CHANGED }
