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
    // 必须显式开启：outDir 在 Vite root 之外，默认不会清空，导致每次构建都把旧的
    // index-<hash>.js/css 留在 static/assets 里永久堆积（实测已积累 58 个文件、约 10MB）。
    // 该目录里只有前端构建产物（index.html + assets/），清空是安全的，且二者都在 .gitignore 中。
    emptyOutDir: true,
    assetsDir: 'assets'
  }
})
