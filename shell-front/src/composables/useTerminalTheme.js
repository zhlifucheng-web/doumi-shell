import { computed, ref } from 'vue'
import { BUILTIN_TERMINAL_PRESETS } from '@/utils/terminalPresets'
import { parseTerminalColorScheme } from '@/utils/terminalThemeParser'
import { useTheme } from '@/composables/useTheme'
import {
  loadThemeStorage,
  saveThemePreference,
  resolveActiveTheme,
  CUSTOM_THEMES_KEY,
  DEFAULT_DARK_THEME_ID,
  DEFAULT_LIGHT_THEME_ID
} from '@/utils/terminalThemeStorage'

// 全局单例状态，保证多终端 Tab 与设置弹窗响应一致
const darkThemeId = ref(DEFAULT_DARK_THEME_ID)
const lightThemeId = ref(DEFAULT_LIGHT_THEME_ID)
const customThemes = ref([])
let initialized = false

function loadStorage() {
  if (initialized || typeof window === 'undefined') return
  initialized = true

  const res = loadThemeStorage(localStorage, BUILTIN_TERMINAL_PRESETS)
  darkThemeId.value = res.darkThemeId
  lightThemeId.value = res.lightThemeId
  customThemes.value = res.customThemes
}

export function useTerminalTheme() {
  loadStorage()
  const { effectiveDark } = useTheme()

  const allThemes = computed(() => {
    return [...BUILTIN_TERMINAL_PRESETS, ...customThemes.value]
  })

  // 深色模式下绑定的终端主题对象
  const darkTheme = computed(() => {
    return allThemes.value.find(t => t.id === darkThemeId.value) ||
      allThemes.value.find(t => t.id === DEFAULT_DARK_THEME_ID) ||
      BUILTIN_TERMINAL_PRESETS[0]
  })

  // 浅色模式下绑定的终端主题对象
  const lightTheme = computed(() => {
    return allThemes.value.find(t => t.id === lightThemeId.value) ||
      allThemes.value.find(t => t.id === DEFAULT_LIGHT_THEME_ID) ||
      allThemes.value.find(t => t.category === 'light') ||
      BUILTIN_TERMINAL_PRESETS[0]
  })

  // 当前生效的主题ID：根据当前页面是深色模式还是浅色模式，分别自适应对应绑定的配色
  const activeThemeId = computed(() => {
    return effectiveDark.value ? darkThemeId.value : lightThemeId.value
  })

  // 当前生效的主题对象：当切换网页深/浅色时，自动无缝切换，无需用户每次手动设置
  const activeTheme = computed(() => {
    return resolveActiveTheme({
      effectiveDark: effectiveDark.value,
      darkThemeId: darkThemeId.value,
      lightThemeId: lightThemeId.value,
      allThemes: allThemes.value
    })
  })

  function setDarkTheme(id) {
    darkThemeId.value = id
    saveThemePreference(localStorage, 'dark', id)
  }

  function setLightTheme(id) {
    lightThemeId.value = id
    saveThemePreference(localStorage, 'light', id)
  }

  function setTheme(id, targetMode = null) {
    const mode = targetMode || (effectiveDark.value ? 'dark' : 'light')
    if (mode === 'light') {
      setLightTheme(id)
    } else {
      setDarkTheme(id)
    }
  }

  function importTheme(content, filename = '', targetMode = null) {
    const parsed = parseTerminalColorScheme(content, filename)
    if (!parsed) {
      throw new Error('未能识别有效的终端或 Zsh 配色格式，请检查文件或内容')
    }

    const newId = `imported-${Date.now()}`
    const themeToSave = {
      ...parsed,
      id: newId,
      category: parsed.category || 'custom',
      tag: '导入配色'
    }

    customThemes.value.push(themeToSave)
    try {
      localStorage.setItem(CUSTOM_THEMES_KEY, JSON.stringify(customThemes.value))
    } catch {
      /* ignore */
    }

    const isLight = targetMode ? (targetMode === 'light') : (parsed.category === 'light' || (!effectiveDark.value && parsed.category !== 'dark'))
    setTheme(newId, isLight ? 'light' : 'dark')
    return themeToSave
  }

  function removeCustomTheme(id) {
    const index = customThemes.value.findIndex(t => t.id === id)
    if (index !== -1) {
      customThemes.value.splice(index, 1)
      try {
        localStorage.setItem(CUSTOM_THEMES_KEY, JSON.stringify(customThemes.value))
      } catch {
        /* ignore */
      }
      if (darkThemeId.value === id) {
        setDarkTheme(DEFAULT_DARK_THEME_ID)
      }
      if (lightThemeId.value === id) {
        setLightTheme(DEFAULT_LIGHT_THEME_ID)
      }
    }
  }

  function exportThemeJson(theme) {
    const clean = { ...theme }
    delete clean.id
    delete clean.tag
    delete clean.category
    return JSON.stringify(clean, null, 2)
  }

  return {
    activeThemeId,
    activeTheme,
    darkThemeId,
    lightThemeId,
    darkTheme,
    lightTheme,
    allThemes,
    customThemes,
    builtinThemes: BUILTIN_TERMINAL_PRESETS,
    setTheme,
    setDarkTheme,
    setLightTheme,
    importTheme,
    removeCustomTheme,
    exportThemeJson
  }
}
