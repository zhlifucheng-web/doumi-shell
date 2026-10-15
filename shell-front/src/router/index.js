import { createRouter, createWebHashHistory } from 'vue-router'
import SshConsole from '@/views/ssh/SshConsole.vue'

const routes = [
  { path: '/', redirect: '/terminal' },
  ...['terminal', 'files', 'scripts', 'processes', 'disk-usage'].map(panel => ({
    path: `/${panel}`, name: panel, component: SshConsole, meta: { panel }
  })),
  { path: '/data-management', component: () => import('@/views/DataManagement.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/terminal' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
