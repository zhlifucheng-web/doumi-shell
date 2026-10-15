import { createApp, watch } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { initTheme, useTheme } from './composables/useTheme'
import { installVersionRefresh } from './utils/versionRefresh'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

initTheme()
const { effectiveDark } = useTheme()
watch(
  effectiveDark,
  (dark) => {
    document.documentElement.classList.toggle('dark', dark)
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light')
  },
  { immediate: true }
)

app.use(router)
app.use(ElementPlus)
app.mount('#app')
installVersionRefresh()
