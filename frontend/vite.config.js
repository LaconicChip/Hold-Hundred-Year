import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Vite 配置：Vue3 + 后端代理
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // 支持组件中以 @ 指向 src（如 @/assets/icons.svg）
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 后端 REST 接口转发
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 后端 WebSocket（STOMP）转发，需开启 ws
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
        changeOrigin: true
      }
    }
  }
})
