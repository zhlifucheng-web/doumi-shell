import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'

const THEME_KEY = 'doumi-theme-preference'
const BACKGROUND_KEY = 'doumi-background'
const defaultBackground = () => ({ color: '#eaf0f8', image: '', opacity: 90, enabled: false })

export const useWorkspaceStore = defineStore('workspace', () => {
  const managedServer = ref(null)
  const fileLocation = ref(null)
  // Active SFTP listing and viewer callback are session-only, never persisted.
  const fileSearchContext = shallowRef(null)
  const preference = ref('system')
  const systemDark = ref(false)
  const background = ref(defaultBackground())
  const aiOpen = ref(false)
  const aiInitialPrompt = ref('')
  const terminalThemeOpen = ref(false)
  const terminalCols = ref(120)
  const terminalRows = ref(34)
  const terminalConnected = ref(false)
  let initialized = false
  let mediaQuery

  function openAi(prompt = '') {
    aiInitialPrompt.value = prompt
    aiOpen.value = true
  }

  function initialize() {
    if (initialized || typeof window === 'undefined') return
    initialized = true
    try {
      const params = new URLSearchParams(window.location.search)
      const qTheme = params.get('theme')
      if (['light', 'dark', 'system'].includes(qTheme)) {
        preference.value = qTheme
      } else {
        const theme = localStorage.getItem(THEME_KEY)
        if (['light', 'dark', 'system'].includes(theme)) preference.value = theme
      }
      if (params.get('dialog') === 'terminalTheme') {
        terminalThemeOpen.value = true
      }
      if (params.has('bgColor')) {
        background.value = {
          enabled: true,
          color: params.get('bgColor'),
          image: '',
          opacity: Number(params.get('opacity')) || 90
        }
      } else {
        const saved = JSON.parse(localStorage.getItem(BACKGROUND_KEY) || 'null')
        if (saved) background.value = {
          enabled: saved.enabled !== false,
          color: /^#[\da-f]{6}$/i.test(saved.color) ? saved.color : '#eaf0f8',
          image: /^data:image\/(png|jpeg|webp);base64,/.test(saved.image) ? saved.image : '',
          opacity: Math.max(60, Math.min(100, Number(saved.opacity) || 90))
        }
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
  return {
    managedServer,
    fileLocation,
    fileSearchContext,
    preference,
    systemDark,
    background,
    effectiveDark,
    themeLabel,
    backgroundStyle,
    aiOpen,
    aiInitialPrompt,
    terminalThemeOpen,
    terminalCols,
    terminalRows,
    terminalConnected,
    openAi,
    initialize,
    setPreference,
    saveBackground,
    resetBackground
  }
})
