import { BUILTIN_TERMINAL_PRESETS } from './terminalPresets.js'

export const DARK_THEME_KEY = 'doumi-term-dark-theme-id'
export const LIGHT_THEME_KEY = 'doumi-term-light-theme-id'
export const LEGACY_THEME_KEY = 'doumi-term-theme-id'
export const CUSTOM_THEMES_KEY = 'doumi-term-custom-themes'

export const DEFAULT_DARK_THEME_ID = 'doumi-obsidian'
export const DEFAULT_LIGHT_THEME_ID = 'doumi-paper'

/**
 * 从存储中读取深色与浅色独立配色偏好及自定义主题
 * 兼容旧版本单一 doumi-term-theme-id
 */
export function loadThemeStorage(storage = (typeof localStorage !== 'undefined' ? localStorage : null), builtinPresets = BUILTIN_TERMINAL_PRESETS) {
  let customThemes = []
  let darkThemeId = DEFAULT_DARK_THEME_ID
  let lightThemeId = DEFAULT_LIGHT_THEME_ID

  if (!storage) {
    return { darkThemeId, lightThemeId, customThemes }
  }

  try {
    const savedCustom = JSON.parse(storage.getItem(CUSTOM_THEMES_KEY) || '[]')
    if (Array.isArray(savedCustom)) {
      customThemes = savedCustom
    }
  } catch {
    /* ignore parsing errors */
  }

  const all = [...builtinPresets, ...customThemes]
  const savedDark = storage.getItem(DARK_THEME_KEY)
  const savedLight = storage.getItem(LIGHT_THEME_KEY)
  const legacy = storage.getItem(LEGACY_THEME_KEY)

  // 1. 解析深色配置
  if (savedDark && all.some(t => t.id === savedDark)) {
    darkThemeId = savedDark
  } else if (legacy) {
    const legacyTheme = all.find(t => t.id === legacy)
    if (legacyTheme && legacyTheme.category !== 'light') {
      darkThemeId = legacy
    }
  }

  // 2. 解析浅色配置
  if (savedLight && all.some(t => t.id === savedLight)) {
    lightThemeId = savedLight
  } else if (legacy) {
    const legacyTheme = all.find(t => t.id === legacy)
    if (legacyTheme && legacyTheme.category === 'light') {
      lightThemeId = legacy
    }
  }

  return { darkThemeId, lightThemeId, customThemes }
}

/**
 * 保存指定模式 (dark / light) 的配色绑定
 */
export function saveThemePreference(storage, mode, themeId) {
  if (!storage || !themeId) return
  try {
    if (mode === 'light') {
      storage.setItem(LIGHT_THEME_KEY, themeId)
    } else {
      storage.setItem(DARK_THEME_KEY, themeId)
    }
    // 保持对旧版本的只读兼容
    storage.setItem(LEGACY_THEME_KEY, themeId)
  } catch {
    /* ignore */
  }
}

/**
 * 根据当前网页深/浅色模式 (effectiveDark) 解析最终生效的终端配色对象
 */
export function resolveActiveTheme({
  effectiveDark,
  darkThemeId,
  lightThemeId,
  allThemes = BUILTIN_TERMINAL_PRESETS
}) {
  const targetId = effectiveDark ? darkThemeId : lightThemeId
  let match = allThemes.find(t => t.id === targetId)

  if (!match) {
    const fallbackId = effectiveDark ? DEFAULT_DARK_THEME_ID : DEFAULT_LIGHT_THEME_ID
    match = allThemes.find(t => t.id === fallbackId) ||
      allThemes.find(t => effectiveDark ? t.category !== 'light' : t.category === 'light') ||
      allThemes[0]
  }

  return match
}
