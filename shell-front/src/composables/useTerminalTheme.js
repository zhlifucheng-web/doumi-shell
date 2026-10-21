import { computed, ref, watch } from 'vue'
import { BUILTIN_TERMINAL_PRESETS } from '@/utils/terminalPresets'
import { parseTerminalColorScheme } from '@/utils/terminalThemeParser'
import { useTheme } from '@/composables/useTheme'

const ACTIVE_THEME_KEY = 'doumi-term-theme-id'
const CUSTOM_THEMES_KEY = 'doumi-term-custom-themes'

// 全局单例状态，保证多终端 Tab 与设置弹窗响应一致
const activeThemeId = ref('')
const customThemes = ref([])
let initialized = false

function loadStorage() {
  if (initialized || typeof window === 'undefined') return
  initialized = true

  try {
    const savedActive = localStorage.getItem(ACTIVE_THEME_KEY)
    if (savedActive) activeThemeId.value = savedActive

    const savedCustom = JSON.parse(localStorage.getItem(CUSTOM_THEMES_KEY) || '[]')
    if (Array.isArray(savedCustom)) {
      customThemes.value = savedCustom
    }
  } catch {
    /* ignore storage errors */
  }
}

export function useTerminalTheme() {
  loadStorage()
  const { effectiveDark } = useTheme()

  const allThemes = computed(() => {
    return [...BUILTIN_TERMINAL_PRESETS, ...customThemes.value]
  })

  // 如果用户未显式选定或所选不存在，则按深色/浅色自适应默认主题
  const activeTheme = computed(() => {
    let match = allThemes.value.find(t => t.id === activeThemeId.value)
    if (!match) {
      const defaultId = effectiveDark.value ? 'doumi-obsidian' : 'doumi-paper'
      match = allThemes.value.find(t => t.id === defaultId) || BUILTIN_TERMINAL_PRESETS[0]
    }
    return match
  })

  function setTheme(id) {
    activeThemeId.value = id
    try {
      localStorage.setItem(ACTIVE_THEME_KEY, id)
    } catch {
      /* ignore */
    }
  }

  function importTheme(content, filename = '') {
    const parsed = parseTerminalColorScheme(content, filename)
    if (!parsed) {
      throw new Error('未能识别有效的终端或 Zsh 配色格式，请检查文件或内容')
    }

    // 检查是否重名或存在相同 ID
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

    setTheme(newId)
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
      if (activeThemeId.value === id) {
        setTheme(effectiveDark.value ? 'doumi-obsidian' : 'doumi-paper')
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
    allThemes,
    customThemes,
    builtinThemes: BUILTIN_TERMINAL_PRESETS,
    setTheme,
    importTheme,
    removeCustomTheme,
    exportThemeJson
  }
}
