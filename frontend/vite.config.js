import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import compression from 'vite-plugin-compression'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [
    vue(),
    // P4.1：Element Plus 按需引入（组件 + 指令 + 显式 API 的样式随用随引），
    // 替代 main.js 里全量注册 + element-plus/dist/index.css（首屏 gzip −200kB）。
    // ElMessage / ElMessageBox 等命令式 API 仍由各文件显式 import，
    // 其样式在 main.js 里显式引一次，避免依赖解析器改名时机。
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
    // P4.15：产出 .gz 预压缩副本（部署端 nginx 直接 send_file 即可，省 CPU）。
    // 只在部署侧生效：本地 dev/未配 gzip 的静态服务不受影响。
    compression({ algorithm: 'gzip', threshold: 1024, deleteOriginFile: false })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    // 第三方依赖拆分为独立 chunk（UX-13）：可并行加载，且升级业务代码时浏览器能复用缓存。
    // ⚠️ P4.1 起**不再**强制把 'element-plus' 整包放进独立 chunk —— 那会把按需
    // 引入tree-shake 掉的组件又拉回来（实测 939kB 不降）；交给 Rollup 按实际引用拆分。
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          echarts: ['echarts']
        }
      }
    },
    chunkSizeWarningLimit: 700
  }
})
