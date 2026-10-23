import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const rawBackend = env.DOUMI_BACKEND || process.env.DOUMI_BACKEND || 'http://10.20.42.116:9081'

  // 规范化基础地址：去除末尾的 /api、/ws 或斜杠，防止用户在配置中多写导致代理路径重复 (如 /api/api)
  const baseOrigin = rawBackend
    .replace(/\/api\/?$/, '')
    .replace(/\/ws\/?$/, '')
    .replace(/\/+$/, '')

  const apiTarget = env.DOUMI_API_TARGET || baseOrigin
  const wsTarget = env.DOUMI_WS_TARGET || baseOrigin

  const proxyConfig = {
    // 请求 /api/xxx -> 转发至 http://10.20.42.116:9081/api/xxx
    '/api': {
      target: apiTarget,
      changeOrigin: true,
      secure: false
    },
    // 请求 /ws/xxx -> 转发至 ws://10.20.42.116:9081/ws/xxx
    '/ws': {
      target: wsTarget,
      changeOrigin: true,
      ws: true,
      secure: false
    }
  }

  return {
    base: '/',
    plugins: [vue()],
    resolve: {
      alias: {
        '@': resolve(__dirname, './src')
      }
    },
    server: {
      host: '0.0.0.0',
      port: 3100,
      open: true,
      proxy: proxyConfig
    },
    preview: {
      host: '0.0.0.0',
      port: 3101,
      proxy: proxyConfig
    }
  }
})
