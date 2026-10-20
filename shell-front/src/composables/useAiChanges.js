import { onMounted, onBeforeUnmount } from 'vue'

// Refresh existing views after AI mutations without remounting terminals or editors.
export function useAiChanges(matches, refresh) {
  const listener = event => {
    if ((event.detail?.operations || []).some(name => matches.test(name))) Promise.resolve(refresh()).catch(() => {})
  }
  onMounted(() => window.addEventListener('doumi-ai-changed', listener))
  onBeforeUnmount(() => window.removeEventListener('doumi-ai-changed', listener))
}
