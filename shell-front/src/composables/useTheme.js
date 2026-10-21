import { storeToRefs } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'

export function initTheme() {
  useWorkspaceStore().initialize()
}

export function useTheme() {
  const store = useWorkspaceStore()
  store.initialize()
  const { preference, effectiveDark, themeLabel } = storeToRefs(store)
  return { preference, effectiveDark, themeLabel, setPreference: store.setPreference }
}
