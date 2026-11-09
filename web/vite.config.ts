import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import UnoCSS from 'unocss/vite'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => ({
  base: loadEnv(mode, process.cwd(), '').VITE_PUBLIC_BASE ?? '/',
  plugins: [vue(), UnoCSS()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: { port: 5273, host: true },
  // 启动时一次性完成全部依赖预打包，避免运行中发现新依赖后
  // 重优化导致的 504 (Outdated Optimize Dep) 与批量清理崩溃
  optimizeDeps: {
    include: [
      'vue',
      'vue-router',
      'pinia',
      'dayjs',
      'ant-design-vue',
      'ant-design-vue/es/locale/zh_CN',
      '@ant-design/icons-vue',
      'qrcode',
      'jsqr',
    ],
  },
}))
