import test from 'node:test'
import assert from 'node:assert/strict'
import {
  loadThemeStorage,
  saveThemePreference,
  resolveActiveTheme,
  DARK_THEME_KEY,
  LIGHT_THEME_KEY,
  LEGACY_THEME_KEY,
  DEFAULT_DARK_THEME_ID,
  DEFAULT_LIGHT_THEME_ID
} from '../src/utils/terminalThemeStorage.js'
import { BUILTIN_TERMINAL_PRESETS } from '../src/utils/terminalPresets.js'

function createMockStorage(initial = {}) {
  const map = new Map(Object.entries(initial))
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
    clear: () => map.clear()
  }
}

test('loadThemeStorage: returns default dark and light themes when storage is empty', () => {
  const storage = createMockStorage()
  const { darkThemeId, lightThemeId, customThemes } = loadThemeStorage(storage, BUILTIN_TERMINAL_PRESETS)

  assert.equal(darkThemeId, DEFAULT_DARK_THEME_ID)
  assert.equal(lightThemeId, DEFAULT_LIGHT_THEME_ID)
  assert.deepEqual(customThemes, [])
})

test('loadThemeStorage: restores independently saved dark and light themes', () => {
  const storage = createMockStorage({
    [DARK_THEME_KEY]: 'dracula',
    [LIGHT_THEME_KEY]: 'solarized-light'
  })

  const { darkThemeId, lightThemeId } = loadThemeStorage(storage, BUILTIN_TERMINAL_PRESETS)
  assert.equal(darkThemeId, 'dracula')
  assert.equal(lightThemeId, 'solarized-light')
})

test('loadThemeStorage: migrates legacy doumi-term-theme-id properly', () => {
  // Legacy set to a dark theme
  const darkLegacyStorage = createMockStorage({
    [LEGACY_THEME_KEY]: 'tokyo-night'
  })
  const res1 = loadThemeStorage(darkLegacyStorage, BUILTIN_TERMINAL_PRESETS)
  assert.equal(res1.darkThemeId, 'tokyo-night')
  assert.equal(res1.lightThemeId, DEFAULT_LIGHT_THEME_ID)

  // Legacy set to a light theme
  const lightLegacyStorage = createMockStorage({
    [LEGACY_THEME_KEY]: 'solarized-light'
  })
  const res2 = loadThemeStorage(lightLegacyStorage, BUILTIN_TERMINAL_PRESETS)
  assert.equal(res2.darkThemeId, DEFAULT_DARK_THEME_ID)
  assert.equal(res2.lightThemeId, 'solarized-light')
})

test('saveThemePreference: updates designated mode and updates legacy key', () => {
  const storage = createMockStorage()

  saveThemePreference(storage, 'dark', 'one-dark')
  assert.equal(storage.getItem(DARK_THEME_KEY), 'one-dark')
  assert.equal(storage.getItem(LEGACY_THEME_KEY), 'one-dark')

  saveThemePreference(storage, 'light', 'doumi-paper')
  assert.equal(storage.getItem(LIGHT_THEME_KEY), 'doumi-paper')
  assert.equal(storage.getItem(LEGACY_THEME_KEY), 'doumi-paper')
})

test('resolveActiveTheme: seamlessly matches theme based on effectiveDark', () => {
  const darkThemeId = 'catppuccin-mocha'
  const lightThemeId = 'solarized-light'

  // When in dark mode:
  const activeDark = resolveActiveTheme({
    effectiveDark: true,
    darkThemeId,
    lightThemeId,
    allThemes: BUILTIN_TERMINAL_PRESETS
  })
  assert.equal(activeDark.id, 'catppuccin-mocha')
  assert.equal(activeDark.name, 'Catppuccin Mocha')

  // When in light mode:
  const activeLight = resolveActiveTheme({
    effectiveDark: false,
    darkThemeId,
    lightThemeId,
    allThemes: BUILTIN_TERMINAL_PRESETS
  })
  assert.equal(activeLight.id, 'solarized-light')
  assert.equal(activeLight.name, 'Solarized Light')
})
