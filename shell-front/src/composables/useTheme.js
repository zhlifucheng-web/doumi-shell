import { computed, ref } from 'vue'

const STORAGE_KEY = 'doumi-theme-preference'

const preference = ref('system')
const systemDark = ref(false)

let inited = false
let mql = null
let mqlListener = null

function readStoredPreference() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === 'light' || raw === 'dark' || raw === 'system') {
      preference.value = raw
    }
  } catch {
    /* ignore */
  }
}

export function initTheme() {
  if (inited) {
    return
  }
  inited = true
  readStoredPreference()
  mql = window.matchMedia('(prefers-color-scheme: dark)')
  systemDark.value = mql.matches
  mqlListener = (e) => {
    systemDark.value = e.matches
  }
  mql.addEventListener('change', mqlListener)
}

export function useTheme() {
  const effectiveDark = computed(() => {
    if (preference.value === 'dark') return true
    if (preference.value === 'light') return false
    return systemDark.value
  })

  const themeLabel = computed(() => {
    if (preference.value === 'light') return '浅色'
    if (preference.value === 'dark') return '深色'
    return '跟随系统'
  })

  function setPreference(pref) {
    preference.value = pref
    try {
      localStorage.setItem(STORAGE_KEY, pref)
    } catch {
      /* ignore */
    }
  }

  return {
    preference,
    effectiveDark,
    themeLabel,
    setPreference
  }
}
