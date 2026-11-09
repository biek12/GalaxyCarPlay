// API 客户端：mock 模式直连 mock/server；生产模式走真实 /web/v1（骨架已就绪，由后端实现）。
import { ApiError } from './http'
import type {
  BindingTicket, Clip, ClipListResult, Job, LiveSession, MeInfo, SessionInfo,
  TelemetryResult, TelemetrySample, Vehicle, VehicleEvent,
} from './types'
import { mockRequest, subscribeEvents as mockSubscribeEvents, liveSample as mockLiveSample } from './mock/server'

const USE_MOCK = import.meta.env.DEV && String(import.meta.env.VITE_USE_MOCK ?? 'false') === 'true'
const API_BASE = String(import.meta.env.VITE_API_BASE ?? '/web/v1')

const TOKEN_KEY = 'ar_web_token'
let token: string | null = USE_MOCK ? localStorage.getItem(TOKEN_KEY) : null
if (!USE_MOCK) localStorage.removeItem(TOKEN_KEY)
function csrfToken() { return document.cookie.split('; ').find(v => v.startsWith('ar_csrf='))?.slice(8) ?? '' }
export function setToken(t: string | null) {
  token = t
  if (USE_MOCK && t) localStorage.setItem(TOKEN_KEY, t)
  else localStorage.removeItem(TOKEN_KEY)
}
export function hasToken() {
  return USE_MOCK ? !!token : true
}

let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn
}

async function request<T>(method: string, path: string, body?: Record<string, unknown>): Promise<T> {
  try {
    if (USE_MOCK) return await mockRequest<T>(method, path, body, token ?? undefined)
    // 真实后端：统一 Cookie 会话 + CSRF 头 + Idempotency-Key
    const res = await fetch(API_BASE + path, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(method !== 'GET' ? { 'Idempotency-Key': crypto.randomUUID(), 'X-CSRF-Token': csrfToken() } : {}),
      },
      credentials: 'include',
      keepalive: method === 'DELETE',
      body: body && method !== 'GET' ? JSON.stringify(body) : undefined,
    })
    if (!res.ok) {
      const data = await res.json().catch(() => ({}))
      const e = data as { code?: string; message?: string; requestId?: string; retryAfterMs?: number }
      if (res.status === 401) onUnauthorized?.()
      throw new ApiError(res.status, e.code ?? 'unknown', e.message ?? `请求失败（${res.status}）`, e)
    }
    if (res.status === 204) return undefined as T
    return (await res.json()) as T
  } catch (e) {
    if (e instanceof ApiError) throw e
    throw new ApiError(0, 'network', '网络错误或服务不可用')
  }
}

export const api = {
  mock: USE_MOCK,
  liveSample: async (id: string, at: number): Promise<TelemetrySample | null> => USE_MOCK ? mockLiveSample(id, at) : request<TelemetrySample | null>('GET', `/vehicles/${id}/live-sample`),

  auth: {
    async login(phone: string) {
      return request<{ challengeId: string; expiresAt: number; next: 'totp' | 'enroll' }>('POST', '/auth/login', { phone })
    },
    // 登录态外的 2FA 绑定：返回 otpauthUri 与密钥（仅此步返回）
    enrollChallenge: (challengeId: string, invitationCode: string) =>
      request<{ otpauthUri: string; secret: string }>('POST', `/auth/challenges/${challengeId}/enroll`, { invitationCode }),
    async verify(challengeId: string, payload: { code?: string; recoveryCode?: string }) {
      const r = await request<{ token?: string; authenticated?: boolean; recoveryCodes?: string[] }>('POST', `/auth/challenges/${challengeId}/verify`, payload)
      setToken(r.token ?? null)
      return r
    },
    // 敏感操作再认证：免密码体系下使用当前 TOTP 验证码
    async reauth(code: string) {
      return request<{ actionTicket: string; expiresAt: number }>('POST', '/auth/reauth', { code })
    },
    async logout() {
      await request('POST', '/auth/logout')
      setToken(null)
    },
  },

  me: {
    get: () => request<MeInfo>('GET', '/me'),
    enrollMfa: (actionTicket: string) => request<{ enrollmentId: string; otpauthUri: string; secret: string }>('POST', '/me/mfa/enrollments', { actionTicket }),
    confirmMfa: (id: string, code: string) => request<{ enabled: boolean; recoveryCodes: string[] }>('POST', `/me/mfa/enrollments/${id}/confirm`, { code }),
    disableMfa: (actionTicket: string) => request('POST', '/me/mfa/disable', { actionTicket }),
    regenerateRecoveryCodes: (actionTicket: string) => request<{ recoveryCodes: string[] }>('POST', '/me/mfa/recovery-codes', { actionTicket }),
    listSessions: () => request<SessionInfo[]>('GET', '/me/sessions'),
    revokeSession: (id: string) => request<{ revokedCurrent: boolean }>('DELETE', `/me/sessions/${id}`),
    revokeAll: () => request('DELETE', '/me/sessions/all'),
  },

  vehicles: {
    list: () => request<Vehicle[]>('GET', '/vehicles'),
    get: (id: string) => request<Vehicle>('GET', `/vehicles/${id}`),
    bind: (code: string) => request<BindingTicket>('POST', '/vehicles/bindings', { code }),
    binding: (id: string) => request<BindingTicket>('GET', `/vehicles/bindings/${id}`),
    wake: (id: string) => request<{ jobId: string }>('POST', `/vehicles/${id}/wake-jobs`),
    sendSignal: (id: string, sid: string, type: 'offer' | 'ice', data: string) => request('POST', `/vehicles/${id}/live-sessions/${sid}/signals`, { type, data }),
    signals: (id: string, sid: string) => request<{signals: {id: string; type: string; data: string}[]}>('GET', `/vehicles/${id}/live-sessions/${sid}/signals`),
    startLive: (id: string) => request<LiveSession>('POST', `/vehicles/${id}/live-sessions`),
    liveView: (id: string, sid: string, flat: Record<string, boolean>) => request('PUT', `/vehicles/${id}/live-sessions/${sid}/view`, { flat }),
    stopLive: (id: string, sid: string) => request('DELETE', `/vehicles/${id}/live-sessions/${sid}`),
    access: (id: string) => request<{ ownerId: string; ownerName: string; role: string; members: { userId: string; displayName: string; role: string; grantedAt: number }[] }>('GET', `/vehicles/${id}/access`),
    removeMember: (id: string, userId: string, actionTicket: string) => request('DELETE', `/vehicles/${id}/access/members/${userId}`, { actionTicket }),
    unbind: (id: string, actionTicket: string) => request('POST', `/vehicles/${id}/unbind`, { actionTicket }),
  },

  clips: {
    prepare: (id: string, cid: string) => request<{jobId: string}>('POST', `/vehicles/${id}/clips/${cid}/prepare`),
    list: (id: string, q: { from: number; until: number; filter?: string; source?: string; cursor?: string }) => {
      const sp = new URLSearchParams({ from: String(q.from), until: String(q.until), filter: q.filter ?? 'all', source: q.source ?? 'all' })
      if (q.cursor) sp.set('cursor', q.cursor)
      return request<ClipListResult>('GET', `/vehicles/${id}/clips?${sp}`)
    },
    get: async (id: string, cid: string) => {
      const clip = await request<Clip>('GET', `/vehicles/${id}/clips/${cid}`)
      if (!USE_MOCK) for (const ch of clip.channels) if (ch.mediaUrl?.startsWith('/web/v1/')) ch.mediaUrl = API_BASE.replace(/\/web\/v1\/?$/, '') + ch.mediaUrl
      return clip
    },
    telemetry: (id: string, cid: string) => request<TelemetryResult>('GET', `/vehicles/${id}/clips/${cid}/telemetry`),
    delete: (id: string, clipIds: string[], scope: 'local' | 'cloud', actionTicket: string) =>
      request<{ jobId: string }>('POST', `/vehicles/${id}/clip-delete-jobs`, { scope, clipIds, actionTicket }),
  },

  sentry: {
    setIntent: (id: string, enabled: boolean, revision: number) =>
      request<{ jobId: string; revision: number }>('PUT', `/vehicles/${id}/sentry-intent`, { enabled, revision }),
  },

  jobs: {
    get: (jobId: string) => request<Job>('GET', `/jobs/${jobId}`),
  },

  events: {
    subscribe: USE_MOCK ? mockSubscribeEvents : (cb: (e: VehicleEvent) => void) => {
      // 真实实现：SSE /web/v1/events，带 Last-Event-ID 断点续传
      const source = new EventSource(API_BASE + '/events', { withCredentials: true })
      source.onmessage = e => { try { cb(JSON.parse(e.data) as VehicleEvent) } catch { /* invalid frame */ } }
      return () => source.close()
    },
  },
}
