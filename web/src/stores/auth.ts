import { defineStore } from 'pinia'
import type { Router } from 'vue-router'
import { api, hasToken, setUnauthorizedHandler } from '@/api/client'
import type { MeInfo } from '@/api/types'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    me: null as MeInfo | null,
    pendingChallengeId: '' as string, // 登录第二步（2FA）暂存的 challenge
    loading: false,
  }),
  getters: {
    loggedIn(): boolean { return hasToken() && !!this.me },
    mfaEnabled(): boolean { return this.me?.mfaEnabled ?? false },
  },
  actions: {
    initUnauthorizedHandler(router: Router) {
      setUnauthorizedHandler(() => {
        this.me = null
        const current = router.currentRoute.value
        if (current.name !== 'login') router.push({ name: 'login', query: { redirect: current.fullPath } })
      })
    },
    async loadMe() {
      if (!hasToken()) { this.me = null; return false }
      try {
        this.me = await api.me.get()
        return true
      } catch {
        this.me = null
        return false
      }
    },
    async login(phone: string) {
      const r = await api.auth.login(phone)
      this.pendingChallengeId = r.challengeId
      return { next: r.next, challengeId: r.challengeId }
    },
    async verify(payload: { code?: string; recoveryCode?: string }) {
      const r = await api.auth.verify(this.pendingChallengeId, payload)
      this.pendingChallengeId = ''
      await this.loadMe()
      return r
    },
    async logout() {
      try { await api.auth.logout() } catch { /* 忽略 */ }
      this.me = null
    },
  },
})
