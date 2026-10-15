import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  base: '/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, './src')
    }
  },
  server: {
    port: 3100,
    open: true,
    proxy: {
      '/api': {
        target: process.env.DOUMI_BACKEND || 'http://127.0.0.1:80',
        changeOrigin: true,
        ws: true
      },
      '/ws': {
        target: process.env.DOUMI_BACKEND || 'http://127.0.0.1:80',
        changeOrigin: true,
        ws: true
      }
    }
  }
})
