import { computed, ref } from 'vue'

const KEY = 'doumi-background'
const defaults = () => ({ color: '#eaf0f8', image: '', opacity: 90, enabled: false })
const background = ref(defaults())
try {
  const saved = JSON.parse(localStorage.getItem(KEY) || 'null')
  if (saved) background.value = {
    enabled: saved.enabled !== false,
    color: /^#[\da-f]{6}$/i.test(saved.color) ? saved.color : '#eaf0f8',
    image: /^data:image\/(png|jpeg|webp);base64,/.test(saved.image) ? saved.image : '',
    opacity: Math.max(60, Math.min(100, Number(saved.opacity) || 90))
  }
} catch { /* Use the default background. */ }
export function useBackground() {
  const style = computed(() => ({
    '--doumi-background-color': background.value.enabled ? background.value.color : 'var(--el-bg-color-page)',
    '--doumi-background-image': background.value.image ? `url("${background.value.image}")` : 'none',
    '--doumi-surface-opacity': `${background.value.opacity}%`
  }))
  function save(value) {
    // Persist first so quota errors do not silently lose the user's preference.
    const next = { ...value, enabled: true }
    localStorage.setItem(KEY, JSON.stringify(next))
    background.value = next
  }
  function reset() { localStorage.removeItem(KEY); background.value = defaults() }
  return { background, style, save, reset }
}
