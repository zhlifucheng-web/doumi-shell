import { storeToRefs } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'

export function useBackground() {
  const store = useWorkspaceStore()
  store.initialize()
  const { background, backgroundStyle: style } = storeToRefs(store)
  return { background, style, save: store.saveBackground, reset: store.resetBackground }
}
