import { createRouter, createWebHashHistory } from 'vue-router'
import SshConsole from '@/views/ssh/SshConsole.vue'
import About from '@/views/About.vue'

const routes = [
  { path: '/', redirect: '/terminal' },
  { path: '/files', redirect: '/terminal' },
  ...['terminal', 'scripts', 'processes', 'disk-usage'].map(panel => ({
    path: `/${panel}`, name: panel, component: SshConsole, meta: { panel }
  })),
  { path: '/data-management', component: () => import('@/views/DataManagement.vue') },
  { path: '/about', name: 'about', component: About },
  { path: '/help', name: 'help', component: () => import('@/views/Help.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/terminal' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
