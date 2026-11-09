import { createRouter, createWebHistory } from 'vue-router'
import { hasToken } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/features/security/LoginPage.vue'), meta: { public: true } },
    { path: '/security/setup', name: 'setup', component: () => import('@/features/security/SecuritySetupPage.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('@/components/AppLayout.vue'),
      children: [
        { path: '', redirect: '/vehicles' },
        { path: 'vehicles', name: 'vehicles', component: () => import('@/features/vehicles/VehiclesPage.vue') },
        { path: 'vehicles/:id', name: 'vehicle-detail', component: () => import('@/features/vehicles/VehicleDetailPage.vue') },
        { path: 'vehicles/:id/live', name: 'live', component: () => import('@/features/live/LivePage.vue') },
        { path: 'vehicles/:id/history', name: 'history', component: () => import('@/features/history/HistoryPage.vue') },
        { path: 'vehicles/:id/clips/:clip', name: 'clip', component: () => import('@/features/playback/PlaybackPage.vue') },
        { path: 'vehicles/:id/sentry', name: 'sentry', component: () => import('@/features/sentry/SentryPage.vue') },
        { path: 'vehicles/:id/access', name: 'access', component: () => import('@/features/vehicles/AccessPage.vue') },
        { path: 'security', name: 'security', component: () => import('@/features/security/SecurityPage.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/vehicles' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.path === '/vehicles' && to.hash.startsWith('#bind=')) {
    const code = new URLSearchParams(to.hash.slice(1)).get('bind') ?? ''
    if (/^[A-Za-z0-9_-]{16}$/.test(code)) sessionStorage.setItem('ar_pending_binding', JSON.stringify({ code, until: Date.now() + 300_000 }))
    return { path: to.path, query: to.query, hash: '', replace: true }
  }
  if (to.meta.public) {
    // 已登录访问登录页 → 跳首页
    if (to.name === 'login' && hasToken() && auth.me) return '/vehicles'
    return true
  }
  if (!hasToken()) return { name: 'login', query: { redirect: to.fullPath } }
  if (!auth.me) {
    const ok = await auth.loadMe()
    if (!ok) return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 未启用 2FA 的账号只能进入安全设置完成绑定
  if (!auth.mfaEnabled && to.name !== 'security') {
    return { name: 'security', query: { mfa: 'required' } }
  }
  return true
})

export default router
