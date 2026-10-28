import { computed, onActivated, onDeactivated, onScopeDispose, ref, watch } from 'vue'

export const FOLLOW_STORAGE_KEY = 'ssh_file_follow_terminal'
const absolutePath = value => typeof value === 'string' && value.startsWith('/') && !value.includes('\0')
const validId = value => Number.isInteger(value) && value >= 0 && value <= 2147483647

/** Owns one probe at a time, including across tab/token changes. */
export function useTerminalFileContext({ sessionToken, tabId, active, blocked, currentPath, request, onNavigate,
  storage = globalThis.localStorage, schedule = setTimeout, unschedule = clearTimeout }) {
  let saved
  try { saved = storage?.getItem(FOLLOW_STORAGE_KEY) } catch { /* Restricted storage. */ }
  const follow = ref(saved !== 'false'), context = ref(null), message = ref('正在检测终端目录…'), activated = ref(true)
  const canAssignOwner = computed(() => !!context.value?.available && validId(context.value.terminalUid) && validId(context.value.terminalGid))
  let generation = 0, timer = null, inFlight = false, disposed = false, controller = null, lastCwd = null
  const running = () => !disposed && activated.value && active() && !!sessionToken()
  function cancelTimer() { if (timer != null) unschedule(timer); timer = null }
  function later(delay) {
    cancelTimer()
    if (!running()) return
    timer = schedule(() => { timer = null; return poll() }, delay)
    timer?.unref?.()
  }
  function navigate() {
    const cwd = context.value?.cwd
    if (!running() || !follow.value || blocked() || !absolutePath(cwd) || lastCwd === cwd) return
    lastCwd = cwd
    if (currentPath() !== cwd) onNavigate(cwd)
  }
  async function poll() {
    if (!running() || inFlight) return
    const version = generation, token = sessionToken(), tab = tabId()
    inFlight = true; controller = new AbortController()
    const current = () => running() && version === generation && token === sessionToken() && tab === tabId()
    try {
      const result = await request(token, { signal: controller.signal })
      if (!current()) return
      if (result?.available && absolutePath(result.cwd)) {
        context.value = result; message.value = ''; navigate()
      } else {
        context.value = null; lastCwd = null
        message.value = result?.message || '无法检测终端目录，保留当前文件目录'
      }
    } catch (error) {
      if (!current()) return
      context.value = null; lastCwd = null
      message.value = error?.message || '终端目录检测暂不可用，保留当前文件目录'
    } finally {
      inFlight = false; controller = null
      if (running()) later(version === generation ? 1000 : 0)
    }
  }
  watch(() => [sessionToken(), tabId(), active(), activated.value], () => {
    ++generation; cancelTimer(); controller?.abort(); context.value = null; lastCwd = null
    message.value = '正在检测终端目录…'
    if (running()) void poll()
  }, { immediate: true, flush: 'sync' })
  watch(follow, value => {
    try { storage?.setItem(FOLLOW_STORAGE_KEY, String(value)) } catch { /* Restricted storage. */ }
    lastCwd = null
    if (value) navigate()
  }, { flush: 'sync' })
  watch(blocked, navigate)
  onDeactivated(() => { activated.value = false })
  onActivated(() => { activated.value = true })
  onScopeDispose(() => { disposed = true; ++generation; cancelTimer(); controller?.abort() })
  return { follow, context, message, canAssignOwner }
}
