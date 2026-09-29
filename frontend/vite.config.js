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
  build: {
    rollupOptions: {
      output: {
        // 第三方大依赖按缓存粒度拆块：改版业务代码时不必让用户重下整个 vendor
        // （echarts 只在带图表的页面按需加载；element-plus 由 main.js 全量注册，随首屏）
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('echarts') || id.includes('zrender')) return 'vendor-echarts'
          if (id.includes('element-plus')) return 'vendor-element'
          if (id.includes('@vue/') || id.includes('/vue/') || id.includes('vue-router') || id.includes('pinia')) {
            return 'vendor-vue'
          }
          return 'vendor'
        }
      }
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
