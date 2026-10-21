import { defineStore } from 'pinia'
import { loadMonitorConfig, saveAccountMonitorConfig } from '@/utils/monitorConfigStore'

export const useMonitorConfigStore = defineStore('monitorConfig', {
  state: () => ({
    configs: {},
    loadingAccounts: {},
    savingAccounts: {},
    editorStates: {}
  }),
  actions: {
    ensure(accountId) {
      const key = String(accountId ?? 'unassigned')
      if (!this.configs[key]) {
        const cards = ['cpu', 'memory', 'network', 'gpu', 'disk']
        this.configs[key] = { revision: 0, customScripts: [], interval: 5000, dashboards: [{ id: 'default', name: '默认面板', selectedCards: cards, customCardIds: [], cardOrder: cards, minutes: 5, iface: '' }] }
      }
      return this.configs[key]
    },
    editor(accountId) {
      const key = String(accountId ?? 'unassigned')
      if (!this.editorStates[key]) this.editorStates[key] = { activeDashboardId: 'default', selectedCards: ['cpu', 'memory', 'network', 'gpu', 'disk'], visibleCustomCardIds: [], cardOrder: ['cpu', 'memory', 'network', 'gpu', 'disk'], interval: 5000, minutes: 5, iface: '', revision: 0 }
      return this.editorStates[key]
    },
    async load(accountId) {
      const key = String(accountId)
      this.loadingAccounts[key] = true
      try {
        const config = await loadMonitorConfig(accountId)
        this.configs[key] = config
        return config
      } finally {
        this.loadingAccounts[key] = false
      }
    },
    async save(accountId, config, sourceId) {
      const key = String(accountId)
      this.savingAccounts[key] = true
      try {
        const saved = await saveAccountMonitorConfig(accountId, config, sourceId)
        const current = this.configs[key]
        if (current?.dashboards?.length) current.revision = Number(saved?.revision) || current.revision
        else this.configs[key] = saved
        return saved
      } finally {
        this.savingAccounts[key] = false
      }
    }
  }
})
