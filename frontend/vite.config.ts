import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 前端统一走 /api，代理到 Java 后端。
      // 可用 STOCKCAT_API_TARGET 覆盖，便于在不动 8080 常驻实例的前提下另起端口验证。
      '/api': {
        target: process.env.STOCKCAT_API_TARGET ?? 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
