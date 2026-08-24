import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期：代理 /api 到 Spring Boot 后端
// 生产构建：产物输出到 Spring Boot 静态资源目录，由后端托管
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
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: false,
    assetsDir: 'assets'
  }
})
