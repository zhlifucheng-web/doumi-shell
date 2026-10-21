import { defineStore } from 'pinia'
import { checkLatestRelease } from '@/utils/versionRefresh'

export const useReleaseStore = defineStore('release', {
  state: () => ({ latest: null, checking: false, error: '' }),
  actions: {
    async check({ refresh = false } = {}) {
      if (this.checking) return this.latest
      this.checking = true
      this.error = ''
      try {
        this.latest = await checkLatestRelease({ refresh })
        return this.latest
      } catch (error) {
        this.error = error?.message || '无法完成版本检查'
        throw error
      } finally { this.checking = false }
    }
  }
})
