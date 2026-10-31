import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端没有 CORS 配置，开发环境通过代理把 /api 转发到 8080
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
