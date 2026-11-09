import { defineStore } from 'pinia'

// 日夜主题：跟随系统 / 浅色 / 深色（对应 App P10 设置口径）
function systemDark(): boolean {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

export const useAppStore = defineStore('app', {
  state: () => ({
    theme: (localStorage.getItem('ar_theme') as 'system' | 'light' | 'dark') || 'system',
  }),
  getters: {
    isDark(state): boolean {
      return state.theme === 'dark' || (state.theme === 'system' && systemDark())
    },
  },
  actions: {
    setTheme(t: 'system' | 'light' | 'dark') {
      this.theme = t
      localStorage.setItem('ar_theme', t)
      this.apply()
    },
    apply() {
      document.documentElement.classList.toggle('dark', this.isDark)
      document.documentElement.style.colorScheme = this.isDark ? 'dark' : 'light'
    },
    init() {
      this.apply()
      window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener('change', () => this.apply())
    },
  },
})
