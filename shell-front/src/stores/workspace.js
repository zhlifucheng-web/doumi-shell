import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

const THEME_KEY = 'doumi-theme-preference'
const BACKGROUND_KEY = 'doumi-background'
const defaultBackground = () => ({ color: '#eaf0f8', image: '', opacity: 90, enabled: false })

export const useWorkspaceStore = defineStore('workspace', () => {
  const managedServer = ref(null)
  const fileLocation = ref(null)
  const preference = ref('system')
  const systemDark = ref(false)
  const background = ref(defaultBackground())
  let initialized = false
  let mediaQuery

  function initialize() {
    if (initialized || typeof window === 'undefined') return
    initialized = true
    try {
      const theme = localStorage.getItem(THEME_KEY)
      if (['light', 'dark', 'system'].includes(theme)) preference.value = theme
      const saved = JSON.parse(localStorage.getItem(BACKGROUND_KEY) || 'null')
      if (saved) background.value = {
        enabled: saved.enabled !== false,
        color: /^#[\da-f]{6}$/i.test(saved.color) ? saved.color : '#eaf0f8',
        image: /^data:image\/(png|jpeg|webp);base64,/.test(saved.image) ? saved.image : '',
        opacity: Math.max(60, Math.min(100, Number(saved.opacity) || 90))
      }
    } catch { /* Use in-memory defaults when browser storage is unavailable. */ }
    mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    systemDark.value = mediaQuery.matches
    mediaQuery.addEventListener('change', event => { systemDark.value = event.matches })
  }

  const effectiveDark = computed(() => preference.value === 'dark' || (preference.value === 'system' && systemDark.value))
  const themeLabel = computed(() => ({ light: '浅色', dark: '深色', system: '跟随系统' })[preference.value])
  const backgroundStyle = computed(() => ({
    '--doumi-background-color': background.value.enabled ? background.value.color : 'var(--el-bg-color-page)',
    '--doumi-background-image': background.value.image ? `url("${background.value.image}")` : 'none',
    '--doumi-surface-opacity': `${background.value.opacity}%`,
    '--doumi-surface': `color-mix(in srgb, var(--el-bg-color) ${background.value.opacity}%, transparent)`
  }))

  function setPreference(value) {
    initialize()
    preference.value = value
    try { localStorage.setItem(THEME_KEY, value) } catch { /* Preference remains active for this session. */ }
  }
  function saveBackground(value) {
    initialize()
    const next = { ...value, enabled: true }
    localStorage.setItem(BACKGROUND_KEY, JSON.stringify(next))
    background.value = next
  }
  function resetBackground() {
    initialize()
    localStorage.removeItem(BACKGROUND_KEY)
    background.value = defaultBackground()
  }

  initialize()
  return { managedServer, fileLocation, preference, systemDark, background, effectiveDark, themeLabel, backgroundStyle,
    initialize, setPreference, saveBackground, resetBackground }
})
