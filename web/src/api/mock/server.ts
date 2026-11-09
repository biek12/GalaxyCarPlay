// mock 后端：实现「在线查看网页需求与 Agent 交接 §6」拟定的 /web/v1 契约。
// 仅本地开发/演示使用；生产由 server/galaxyd 提供，前端通过 client.ts 无感切换。
// 仅开发演示：手机号 + TOTP；生产使用独立Cookie账号域。
import { ApiError } from '../http'
import type {
  BindingTicket, Clip, ClipListResult, Direction, Job, LiveSession, MeInfo,
  SessionInfo, TelemetryResult, TimelineEvent, TelemetrySample, Vehicle, VehicleEvent,
} from '../types'
import { DIRECTIONS } from '../types'
import { buildDb, generateTelemetry, hashStr, mulberry32, type ClipRow } from './db'
import { randomSecret, timeStepAt, totpAt } from '@/utils/totp'

const PERSIST_KEY = 'ar_mock_state_v1'
const SCEN_KEY = 'ar_mock_scenarios_v1'
export const DEMO_PHONE = '17760420247'

// —— 场景开关（§7 mock 覆盖清单） ————————————————————————————
export interface Scenarios {
  l6Online: boolean
  rearLost: boolean
  leftOccupied: boolean
  wakeFail: boolean
  flatCalib: boolean
  streamStall: boolean
  deletePartialFail: boolean
  insertNewClip: boolean
  sentryFail: boolean
}

const defaultScenarios: Scenarios = {
  l6Online: true, rearLost: false, leftOccupied: false, wakeFail: false,
  flatCalib: false, streamStall: false, deletePartialFail: true, insertNewClip: false, sentryFail: false,
}

export function loadScenarios(): Scenarios {
  try {
    return { ...defaultScenarios, ...JSON.parse(localStorage.getItem(SCEN_KEY) || '{}') }
  } catch {
    return { ...defaultScenarios }
  }
}
export function saveScenarios(s: Scenarios) {
  localStorage.setItem(SCEN_KEY, JSON.stringify(s))
  notifyListeners()
}

// —— 持久化状态 ————————————————————————————
interface Persisted {
  mfaEnabled: boolean
  mfaSecret: string
  pendingSecret: string
  recoveryCodes: string[]
  sessions: Record<string, { device: string; ipMasked: string; lastActiveAt: number; createdAt: number }>
  watched: Record<string, true>
  boundVehicles: string[]
  usedRecovery: string[]
  usedTotpSteps: number[]
}

function loadPersisted(): Persisted {
  const d: Persisted = {
    mfaEnabled: false, mfaSecret: '', pendingSecret: '', recoveryCodes: [],
    sessions: {}, watched: {}, boundVehicles: [], usedRecovery: [], usedTotpSteps: [],
  }
  try {
    return { ...d, ...JSON.parse(localStorage.getItem(PERSIST_KEY) || '{}') }
  } catch {
    return d
  }
}
function saveP() {
  localStorage.setItem(PERSIST_KEY, JSON.stringify(P))
}
let P = loadPersisted()

// —— 运行时状态 ————————————————————————————
const db = buildDb()
interface Presence { state: 'online' | 'sleeping' | 'waking' | 'offline' | 'unknown' | 'fault'; lastSeenAt: number }
const presence: Record<string, Presence> = {
  'demo-l6': { state: loadScenarios().l6Online ? 'online' : 'sleeping', lastSeenAt: Date.now() - 30_000 },
  'demo-l7': { state: 'sleeping', lastSeenAt: Date.now() - 3 * 3_600_000 },
  'demo-share': { state: 'online', lastSeenAt: Date.now() - 60_000 },
}
const recording: Record<string, { state: 'recording' | 'idle' | 'unknown'; startedAt?: number }> = {
  'demo-l6': { state: 'recording', startedAt: Date.now() - 42 * 60_000 },
  'demo-l7': { state: 'idle' },
  'demo-share': { state: 'idle' },
}
const sentryState: Record<string, { desired: boolean; actual: Vehicle['sentry']['actual']; revision: number }> = {
  'demo-l6': { desired: false, actual: 'disarmed', revision: 1 },
  'demo-l7': { desired: false, actual: 'unknown', revision: 1 },
  'demo-share': { desired: false, actual: 'unknown', revision: 1 },
}
const sentryEvents: Record<string, { at: number; text: string }[]> = { 'demo-l6': [], 'demo-l7': [], 'demo-share': [] }

const challenges = new Map<string, { expiresAt: number; next: 'totp' | 'enroll'; failCount: number; phone?: string; pendingSecret?: string }>()
const enrollments = new Map<string, { secret: string }>()
const actionTickets = new Map<string, { expiresAt: number }>()
const liveSessions = new Map<string, LiveSession & { vehicleId: string }>()
const jobs = new Map<string, Job>()
const bindings = new Map<string, { code: string; issuedAt: number; used: boolean }>()
const loginFails: Record<string, { count: number; lockedUntil: number }> = {}
const telemetryCache = new Map<string, TelemetryResult>()
const eventListeners = new Set<(e: VehicleEvent) => void>()

function notifyListeners() {
  // 场景变化推送（presence / recording 语义由场景决定）
  const s = loadScenarios()
  emit({ type: 'presence', vehicleId: 'demo-l6', payload: { state: s.l6Online ? 'online' : 'sleeping' }, at: Date.now() })
}
function emit(e: VehicleEvent) {
  eventListeners.forEach((fn) => {
    try { fn(e) } catch { /* ignore */ }
  })
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const latency = () => sleep(90 + Math.random() * 180)

function err(status: number, code: string, message: string, retryAfterMs?: number): never {
  throw new ApiError(status, code, message, { retryAfterMs })
}

function requireSession(token?: string) {
  if (!token || !P.sessions[token]) err(401, 'unauthorized', '未登录或会话已失效')
  P.sessions[token].lastActiveAt = Date.now()
}

function requireTicket(ticket?: string) {
  if (!ticket || !actionTickets.has(ticket) || (actionTickets.get(ticket)!.expiresAt < Date.now())) {
    err(403, 'reauth_required', '需要重新认证后才能执行敏感操作')
  }
  actionTickets.delete(ticket!)
}

function genCodes(): string[] {
  return Array.from({ length: 10 }, () => `${crypto.getRandomValues(new Uint32Array(1))[0] % 100000}`.padStart(5, '0') + '-' + `${crypto.getRandomValues(new Uint32Array(1))[0] % 100000}`.padStart(5, '0'))
}

// —— 车辆 DTO ————————————————————————————
function scenarioAware(vehicleId: string): { online: boolean; sleeping: boolean } {
  const s = loadScenarios()
  if (vehicleId === 'demo-l6') return { online: s.l6Online, sleeping: !s.l6Online }
  const p = presence[vehicleId]
  return { online: p.state === 'online', sleeping: p.state === 'sleeping' }
}

function toVehicle(vehicleId: string): Vehicle {
  const row = db.vehicles.find((v) => v.vehicleId === vehicleId)
  const isNew = vehicleId === 'demo-new'
  const { online, sleeping } = scenarioAware(vehicleId)
  const p = presence[vehicleId] ?? { state: online ? 'online' : 'sleeping', lastSeenAt: Date.now() - 60_000 }
  const flat = loadScenarios().flatCalib
  return {
    vehicleId,
    name: isNew ? '银河 L6（新绑定）' : row?.name ?? vehicleId,
    model: row?.model ?? '2023 Max',
    plateMasked: row?.plateMasked ?? '浙A·X**01',
    role: vehicleId === 'demo-share' ? 'viewer' : 'owner',
    presence: { state: p.state, lastSeenAt: p.lastSeenAt },
    capabilities: {
      remoteWake: vehicleId === 'demo-share'
        ? { supported: false, reason: '需车主在车端开启远程唤醒并完成电源适配（演示）' }
        : { supported: true },
      flatView: { front: flat, rear: flat, left: flat, right: flat },
    },
    recording: {
      state: recording[vehicleId]?.state ?? 'unknown',
      startedAt: recording[vehicleId]?.startedAt,
      observedAt: Date.now(),
    },
    storage: vehicleId === 'demo-l6'
      ? { volume: '内置', usedBytes: 21.3e9, quotaBytes: 32e9, freeBytes: 46.8e9 }
      : { volume: '内置', usedBytes: 6.4e9, quotaBytes: 16e9, freeBytes: 71.2e9 },
    sentry: { ...sentryState[vehicleId] },
  }
}

function allVehicles(): Vehicle[] {
  return [...P.boundVehicles, 'demo-l6', 'demo-l7', 'demo-share'].filter((v, i, a) => a.indexOf(v) === i).map(toVehicle)
}

// —— 作业 ————————————————————————————
function createJob(type: Job['type'], vid?: string): Job {
  const job: Job = { jobId: 'job-' + crypto.randomUUID().slice(0, 8), type, state: 'queued', createdAt: Date.now(), updatedAt: Date.now() }
  jobs.set(job.jobId, job)
  return job
}
function updateJob(jobId: string, patch: Partial<Job>) {
  const job = jobs.get(jobId)
  if (!job) return
  Object.assign(job, patch, { updatedAt: Date.now() })
  emit({ type: 'job', jobId, vehicleId: (patch as Partial<Job> & { vehicleId?: string }).vehicleId, payload: { state: job.state }, at: Date.now() })
}

// —— 片段视图 ————————————————————————————
function clipView(c: ClipRow): Clip {
  return {
    clipId: c.clipId, sessionId: c.sessionId, startAt: c.startAt, endAt: c.endAt,
    source: c.source, availability: c.availability,
    locked: c.locked, watched: !!P.watched[c.clipId], eventCount: c.eventCount,
    bytes: c.bytes, channels: c.channels, telemetry: c.telemetry,
  }
}

function syntheticNewestClip(vid: string): ClipRow {
  const now = Date.now()
  const start = Math.floor((now - 40_000) / 1000) * 1000
  return {
    clipId: `${vid}-live-${start}`, sessionId: `${vid}-live`, startAt: start, endAt: now,
    source: 'local', availability: 'ok', locked: false, watched: false, eventCount: 0,
    bytes: 80e6, telemetry: { schema: 'vehicle-feed-v4', actualIntervalMs: 500, availability: 'full' },
    channels: DIRECTIONS.map((direction) => ({ direction, status: 'ok', mediaStartOffsetMs: 0, durationMs: 40_000 })),
    seed: 1, playbackOffsetsMs: { front: 0, rear: 0, left: 0, right: 0 },
  }
}

// —— 实时遥测（live 数据条） ————————————————————————————
export function liveSample(vehicleId: string, tMs: number): TelemetrySample {
  const rng = mulberry32(hashStr(vehicleId) + Math.floor(tMs / 1000))
  const base = 42 + Math.sin(tMs / 15000) * 14
  const brakePulse = Math.floor(tMs / 21000) % 3 === 0 && tMs % 21000 < 2600 ? (tMs % 21000) / 2600 * 72 : 0
  const s = loadScenarios()
  return {
    t: tMs,
    q: s.rearLost && vehicleId === 'demo-l6' ? 'good' : 'good',
    speed: Math.max(0, Math.round((base - brakePulse * 0.22) * 10) / 10),
    gear: 'D',
    rpm: Math.round(950 + base * 20 + brakePulse * 15),
    steer: Math.round(Math.sin(tMs / 6200) * 48),
    throttle: brakePulse > 0 ? 0 : Math.round(Math.max(0, 12 + Math.sin(tMs / 9000) * 10)),
    brake: Math.round(brakePulse),
    turn: (tMs % 47000) > 40000 ? 'left' : (tMs % 31000) > 26000 ? 'right' : 'off',
    laneAssist: tMs % 60000 > 15000 && tMs % 60000 < 38000,
    cruiseOn: tMs % 90000 > 20000,
    cruiseSet: 60,
    beam: 'low',
    fog: false,
    autoHold: false,
    parkBrake: false,
    fuelPct: 58,
    evRangeKm: 396,
    odoKm: 38241 + tMs / 3_600_000,
    tempInC: 23.5,
    tempOutC: 18.2,
  }
}

// —— 唤醒 / 删除 / 哨兵作业编排 ————————————————————————————
function runWakeJob(jobId: string, vehicleId: string) {
  const fail = loadScenarios().wakeFail
  const steps: [Job['state'], number][] = [['dispatched', 500], ['waking', 900], ['device_online', 1800], ['ready', 2600]]
  let t = 0
  for (const [state, delay] of steps) {
    t += delay
    setTimeout(() => {
      if (fail && state === 'device_online') {
        updateJob(jobId, { state: 'failed', reason: '唤醒失败：车辆电源域未响应（演示场景）' })
        return
      }
      updateJob(jobId, { state })
      if (state === 'waking') presence[vehicleId] = { state: 'waking', lastSeenAt: Date.now() }
      if (state === 'device_online') presence[vehicleId] = { state: 'online', lastSeenAt: Date.now() }
      if (state === 'ready') emit({ type: 'presence', vehicleId, payload: { state: 'online' }, at: Date.now() })
    }, t)
  }
}

function runDeleteJob(jobId: string, vid: string, clipIds: string[], scope: 'local' | 'cloud') {
  updateJob(jobId, { state: 'running' })
  setTimeout(() => {
    const list = db.clips[vid] ?? []
    const removed = new Set<string>()
    const items = clipIds.map((cid) => {
      const c = list.find((x) => x.clipId === cid)
      if (!c) return { ref: cid, state: 'skipped' as const, reason: '片段不存在或已被覆盖' }
      if (c.locked) return { ref: cid, state: 'skipped' as const, reason: '已锁定：需先在车端解锁后删除' }
      if (c.availability === 'overwritten' && scope === 'local') return { ref: cid, state: 'skipped' as const, reason: '本地原片已覆盖，仅剩云端索引' }
      if (loadScenarios().deletePartialFail && cid.endsWith('c2-1')) return { ref: cid, state: 'failed' as const, reason: '片段正被回放占用，稍后重试（演示场景）' }
      removed.add(cid)
      return { ref: cid, state: 'success' as const }
    })
    if (removed.size) db.clips[vid] = list.filter((x) => !removed.has(x.clipId))
    const failed = items.filter((i) => i.state === 'failed').length
    updateJob(jobId, {
      state: failed > 0 ? 'partial_success' : 'success',
      progress: { done: items.length, total: items.length },
      items,
    })
  }, 1600)
}

function runSentryJob(jobId: string, vid: string, enabled: boolean, revision: number) {
  updateJob(jobId, { state: 'running' })
  setTimeout(() => {
    if (enabled && loadScenarios().sentryFail) {
      sentryState[vid] = { desired: true, actual: 'failed', revision }
      updateJob(jobId, { state: 'failed', reason: '布防失败：车辆电源条件不满足（演示场景）' })
    } else {
      sentryState[vid] = { desired: enabled, actual: enabled ? 'armed_standby' : 'disarmed', revision }
      updateJob(jobId, { state: 'success' })
      sentryEvents[vid] = [{ at: Date.now(), text: enabled ? '布防指令已生效，哨兵待机' : '已关闭哨兵布防' }, ...sentryEvents[vid]]
    }
    emit({ type: 'sentry', vehicleId: vid, payload: { ...sentryState[vid] }, at: Date.now() })
  }, 2200)
  if (enabled) {
    // 布防后演示「录像中」状态迁移
    setTimeout(() => {
      if (sentryState[vid].actual === 'armed_standby') {
        sentryState[vid].actual = 'armed_recording'
        sentryEvents[vid] = [{ at: Date.now(), text: '检测到异常振动，哨兵开始录像' }, ...sentryEvents[vid]]
        emit({ type: 'sentry', vehicleId: vid, payload: { ...sentryState[vid] }, at: Date.now() })
      }
    }, 6000)
  }
}

// —— 路由 ————————————————————————————
type Body = Record<string, any>

export async function mockRequest<T>(method: string, path: string, body?: Body, token?: string): Promise<T> {
  await latency()
  const now = Date.now()
  const m = method.toUpperCase()
  const [p, qs] = path.split('?')
  const query = new URLSearchParams(qs || '')
  const seg = p.split('/').filter(Boolean)

  // —— 公开路由（无需会话） ——
  if (m === 'POST' && p === '/auth/login') {
    // 免密码登录：仅手机号 + 两步验证
    const phone = (body as { phone?: string }).phone?.trim() ?? ''
    if (!/^\d{11}$/.test(phone)) err(422, 'invalid', '请输入 11 位手机号')
    const f = loginFails[phone] ?? { count: 0, lockedUntil: 0 }
    if (f.lockedUntil > now) err(429, 'throttled', '尝试次数过多，请稍后再试', f.lockedUntil - now)
    delete loginFails[phone]
    const next: 'totp' | 'enroll' = P.mfaEnabled ? 'totp' : 'enroll'
    const id = 'ch-' + crypto.randomUUID().slice(0, 8)
    challenges.set(id, { expiresAt: now + 5 * 60_000, next, failCount: 0, phone })
    return { challengeId: id, expiresAt: now + 5 * 60_000, next } as T
  }

  // 登录态外绑定：为当前 challenge 生成待确认 TOTP 密钥（二维码 + 密钥仅此步返回）
  if (m === 'POST' && seg[0] === 'auth' && seg[1] === 'challenges' && seg[3] === 'enroll') {
    const ch = challenges.get(seg[2])
    if (!ch || ch.expiresAt < now) err(401, 'challenge_expired', '登录会话已过期，请重新登录')
    if (ch.next !== 'enroll') err(409, 'conflict', '该账号已启用两步验证，直接输入验证码即可')
    ch.pendingSecret = randomSecret()
    return {
      otpauthUri: `otpauth://totp/AutoRecord:${encodeURIComponent(ch.phone ?? 'user')}?secret=${ch.pendingSecret}&issuer=AutoRecord`,
      secret: ch.pendingSecret,
    } as T
  }

  if (m === 'POST' && seg[0] === 'auth' && seg[1] === 'challenges' && seg[3] === 'verify') {
    const ch = challenges.get(seg[2])
    if (!ch || ch.expiresAt < now) err(401, 'challenge_expired', '登录会话已过期，请重新登录')
    if (ch.failCount >= 5) err(429, 'throttled', '验证码错误次数过多，请重新登录', 60_000)
    const code = body?.code as string | undefined
    const recovery = body?.recoveryCode as string | undefined
    let newRecoveryCodes: string[] | undefined
    if (ch.next === 'enroll') {
      // 首次登录：验证码必须来自登录页刚绑定的密钥，验证通过即启用
      if (!ch.pendingSecret) err(409, 'mfa_enroll_required', '请先在登录页扫码绑定验证器')
      const step = timeStepAt(now)
      let ok = false
      for (const st of [step - 1, step, step + 1]) {
        if (await totpAt(ch.pendingSecret, st * 30_000) === code) { ok = true; break }
      }
      if (!ok) { ch.failCount++; err(401, 'bad_code', '验证码错误，请核对验证器') }
      P.mfaSecret = ch.pendingSecret
      P.mfaEnabled = true
      P.recoveryCodes = genCodes()
      P.usedTotpSteps = []
      saveP()
      newRecoveryCodes = [...P.recoveryCodes]
    } else if (code) {
      const step = timeStepAt(now)
      const cands = [step - 1, step, step + 1]
      let ok = false
      for (const st of cands) {
        if (P.usedTotpSteps.includes(st)) continue
        if (await totpAt(P.mfaSecret, st * 30_000) === code) { ok = true; P.usedTotpSteps.push(st); break }
      }
      if (!ok) { ch.failCount++; err(401, 'bad_code', '验证码错误') }
    } else if (recovery) {
      if (!P.recoveryCodes.includes(recovery)) err(401, 'bad_recovery_code', '恢复码无效或已被使用')
      P.recoveryCodes = P.recoveryCodes.filter((c) => c !== recovery)
      P.usedRecovery.push(recovery)
      saveP()
    } else err(422, 'invalid', '缺少验证码')
    const tk = 'sess-' + crypto.randomUUID()
    P.sessions[tk] = { device: 'Web 浏览器（本机演示）', ipMasked: '192.168.*.*', lastActiveAt: now, createdAt: now }
    saveP()
    challenges.delete(seg[2])
    return { token: tk, recoveryCodes: newRecoveryCodes } as T
  }

  // 敏感操作再认证：免密码体系下用当前 TOTP 验证码
  if (m === 'POST' && p === '/auth/reauth') {
    requireSession(token)
    if (!P.mfaEnabled) err(403, 'mfa_required', '请先启用两步验证')
    const code = (body as { code?: string }).code
    const step = timeStepAt(now)
    let ok = false
    for (const st of [step - 1, step, step + 1]) {
      if (P.usedTotpSteps.includes(st)) continue
      if (await totpAt(P.mfaSecret, st * 30_000) === code) { ok = true; break }
    }
    if (!ok) err(401, 'bad_code', '验证码错误')
    const t = 'tk-' + crypto.randomUUID().slice(0, 8)
    actionTickets.set(t, { expiresAt: now + 5 * 60_000 })
    return { actionTicket: t, expiresAt: now + 5 * 60_000 } as T
  }

  if (p === '/me' && m === 'GET') {
    requireSession(token)
    return { userId: 'u-demo', displayName: '演示用户', phoneMasked: '138****0000', mfaEnabled: P.mfaEnabled, createdAt: now - 30 * 86_400_000 } as T
  }

  // —— 需要会话的路由 ——
  requireSession(token)

  if (m === 'POST' && p === '/auth/logout') {
    if (token) { delete P.sessions[token]; saveP() }
    return {} as T
  }

  if (m === 'POST' && p === '/me/mfa/enrollments') {
    const secret = randomSecret()
    const id = 'enr-' + crypto.randomUUID().slice(0, 8)
    enrollments.set(id, { secret })
    return { enrollmentId: id, otpauthUri: `otpauth://totp/AutoRecord:demo?secret=${secret}&issuer=AutoRecord`, secret } as T
  }

  if (m === 'POST' && seg[0] === 'me' && seg[1] === 'mfa' && seg[2] === 'enrollments' && seg[4] === 'confirm') {
    const enr = enrollments.get(seg[3])
    if (!enr) err(404, 'not_found', '待确认的两步验证设置不存在或已过期')
    const step = timeStepAt(now)
    const code = (body as { code: string }).code
    let ok = false
    for (const st of [step - 1, step, step + 1]) {
      if (await totpAt(enr.secret, st * 30_000) === code) { ok = true; break }
    }
    if (!ok) err(401, 'bad_code', '验证码错误，未确认的两步验证不会启用')
    P.mfaSecret = enr.secret
    P.mfaEnabled = true
    P.recoveryCodes = genCodes()
    P.usedTotpSteps = []
    saveP()
    enrollments.delete(seg[3])
    return { enabled: true, recoveryCodes: [...P.recoveryCodes] } as T
  }

  if (m === 'POST' && p === '/me/mfa/disable') {
    requireTicket((body as { actionTicket?: string }).actionTicket)
    P.mfaEnabled = false
    saveP()
    return {} as T
  }

  if (m === 'POST' && p === '/me/mfa/recovery-codes') {
    requireTicket((body as { actionTicket?: string }).actionTicket)
    P.recoveryCodes = genCodes()
    saveP()
    return { recoveryCodes: [...P.recoveryCodes] } as T
  }

  if (p === '/me/sessions' && m === 'GET') {
    const list: SessionInfo[] = Object.entries(P.sessions).map(([id, s]) => ({
      id: id.slice(0, 10), device: s.device, ipMasked: s.ipMasked, lastActiveAt: s.lastActiveAt, current: id === token,
    }))
    return list as T
  }
  if (seg[0] === 'me' && seg[1] === 'sessions' && m === 'DELETE') {
    if (seg[2] === 'all') {
      for (const id of Object.keys(P.sessions)) if (id !== token) delete P.sessions[id]
      saveP()
      return {} as T
    }
    const target = Object.keys(P.sessions).find((id) => id.slice(0, 10) === seg[2])
    if (!target) err(404, 'not_found', '会话不存在')
    const wasCurrent = target === token
    delete P.sessions[target]
    saveP()
    return { revokedCurrent: wasCurrent } as T
  }

  // —— 车辆 ——
  if (m === 'POST' && p === '/vehicles/bindings') {
    const code = (body as { code: string }).code?.trim()
    if (!code) err(422, 'invalid', '请输入绑定码')
    if (code === 'BIND-DEMO-OK') {
      const id = 'bd-' + crypto.randomUUID().slice(0, 8)
      bindings.set(id, { code, issuedAt: now, used: false })
      return { bindingId: id, status: 'pending_device_confirmation' } as T
    }
    if (code === 'BIND-DEMO-EXPIRED') {
      const id = 'bd-' + crypto.randomUUID().slice(0, 8)
      bindings.set(id, { code, issuedAt: now - 6 * 60_000, used: false })
      return { bindingId: id, status: 'pending_device_confirmation' } as T
    }
    err(422, 'invalid', '绑定码无效（演示可用 BIND-DEMO-OK / BIND-DEMO-EXPIRED）')
  }
  if (seg[0] === 'vehicles' && seg[1] === 'bindings' && m === 'GET') {
    const b = bindings.get(seg[2])
    if (!b) err(404, 'not_found', '绑定请求不存在')
    if (b.used) return { status: 'rejected', reason: '绑定码已被使用' } as T
    if (now - b.issuedAt > 5 * 60_000) return { status: 'expired', reason: '绑定码已过期（有效期 5 分钟），请在车端重新生成' } as T
    if (now - b.issuedAt > 4_000) {
      b.used = true
      if (!P.boundVehicles.includes('demo-new')) P.boundVehicles.push('demo-new')
      saveP()
      presence['demo-new'] = { state: 'online', lastSeenAt: now }
      recording['demo-new'] = { state: 'idle' }
      sentryState['demo-new'] = { desired: false, actual: 'unknown', revision: 1 }
      return { status: 'confirmed', vehicleId: 'demo-new' } as T
    }
    return { status: 'pending_device_confirmation' } as T
  }

  if (p === '/vehicles' && m === 'GET') return allVehicles() as T
  if (seg[0] === 'vehicles' && seg.length === 2 && m === 'GET') {
    const vid = seg[1]
    if (!allVehicles().some((v) => v.vehicleId === vid)) err(403, 'forbidden', '无权访问该车辆')
    return toVehicle(vid) as T
  }

  if (seg[0] === 'vehicles' && seg[2] === 'wake-jobs' && m === 'POST') {
    const vid = seg[1]
    const v = toVehicle(vid)
    if (!v.capabilities.remoteWake.supported) err(503, 'capability_unavailable', v.capabilities.remoteWake.reason ?? '当前车辆不支持远程唤醒')
    if (v.presence.state === 'online') err(409, 'conflict', '车辆已在线，无需唤醒')
    const job = createJob('wake')
    ;(job as any).vehicleId = vid
    runWakeJob(job.jobId, vid)
    return { jobId: job.jobId } as T
  }

  if (seg[0] === 'vehicles' && seg[2] === 'live-sessions' && m === 'POST') {
    const vid = seg[1]
    const v = toVehicle(vid)
    if (v.presence.state !== 'online') err(503, 'device_unavailable', '车辆不在线，请先唤醒')
    const s = loadScenarios()
    const sess: LiveSession & { vehicleId: string } = {
      iceServers: [],
      vehicleId: vid,
      sessionId: 'live-' + crypto.randomUUID().slice(0, 8),
      expiresAt: now + 10 * 60_000,
      channels: DIRECTIONS.map((direction) => ({
        direction,
        status: direction === 'left' && s.leftOccupied ? ('occupied' as const) : direction === 'rear' && s.rearLost ? ('unavailable' as const) : ('ok' as const),
      })),
    }
    liveSessions.set(sess.sessionId, sess)
    return sess as T
  }
  if (seg[0] === 'vehicles' && seg[2] === 'live-sessions' && m === 'DELETE') {
    liveSessions.delete(seg[3])
    return {} as T
  }

  // —— 片段 ——
  if (seg[0] === 'vehicles' && seg[2] === 'clips' && seg.length === 3 && m === 'GET') {
    const vid = seg[1]
    if (!allVehicles().some((v) => v.vehicleId === vid)) err(403, 'forbidden', '无权访问该车辆')
    const from = Number(query.get('from') ?? 0)
    const until = Number(query.get('until') ?? Date.now())
    if (until <= from) err(422, 'invalid', '结束时间必须大于开始时间')
    const filter = query.get('filter') ?? 'all'
    const source = query.get('source') ?? 'all'
    const cursor = query.get('cursor')
    let list = (db.clips[vid] ?? []).slice()
    const s = loadScenarios()
    if (s.insertNewClip && !cursor) list = [syntheticNewestClip(vid), ...list]
    list.sort((a, b) => b.startAt - a.startAt)
    list = list.filter((c) => c.endAt > from && c.startAt < until)
    if (filter === 'important') list = list.filter((c) => c.locked || c.eventCount > 0)
    if (filter === 'abnormal') list = list.filter((c) => ['partial', 'recoverable', 'missing'].includes(c.availability))
    if (filter === 'unwatched') list = list.filter((c) => !P.watched[c.clipId] && c.availability === 'ok')
    if (source === 'local') list = list.filter((c) => c.source === 'local')
    if (source === 'cloud') list = list.filter((c) => c.source === 'cloud')
    let startIdx = 0
    if (cursor) {
      const [cs] = atob(cursor).split(':')
      const cStart = Number(cs)
      startIdx = list.findIndex((c) => c.startAt < cStart)
      if (startIdx < 0) startIdx = list.length
    }
    const page = list.slice(startIdx, startIdx + 12)
    const nextCursor = startIdx + 12 < list.length ? btoa(`${page[page.length - 1].startAt}:${page[page.length - 1].clipId}`) : null
    const result: ClipListResult = { items: page.map(clipView), nextCursor, indexReady: true }
    return result as T
  }

  if (seg[0] === 'vehicles' && seg[2] === 'clips' && seg.length === 4 && m === 'GET') {
    const vid = seg[1]
    if (!allVehicles().some((v) => v.vehicleId === vid)) err(403, 'forbidden', '无权访问该车辆')
    const c = (db.clips[vid] ?? []).find((x) => x.clipId === seg[3])
    if (!c) err(404, 'not_found', '片段不存在（可能已被覆盖或删除）')
    P.watched[c.clipId] = true
    saveP()
    const clip = clipView(c)
    return clip as T
  }

  if (seg[0] === 'vehicles' && seg[2] === 'clips' && seg[4] === 'telemetry' && m === 'GET') {
    const vid = seg[1]
    const cid = seg[3]
    const c = (db.clips[vid] ?? []).find((x) => x.clipId === cid)
    if (!c) err(404, 'not_found', '片段不存在')
    const key = `${vid}/${cid}`
    if (!telemetryCache.has(key)) {
      const duration = c.endAt - c.startAt
      const crossMidnight = new Date(c.startAt).getDate() !== new Date(c.endAt).getDate()
      const { samples, events } = generateTelemetry(cid, duration, c.telemetry.actualIntervalMs, crossMidnight)
      const sessionClips = (db.clips[vid] ?? [])
        .filter((x) => x.sessionId === c.sessionId)
        .sort((a, b) => a.startAt - b.startAt)
        .map((x) => ({ clipId: x.clipId, startAt: x.startAt, endAt: x.endAt }))
      telemetryCache.set(key, {
        schema: c.telemetry.schema,
        actualIntervalMs: c.telemetry.actualIntervalMs,
        clockBiasUs: (c.seed % 800) - 400,
        samples,
        events,
        sessionClips,
      })
    }
    return telemetryCache.get(key)! as T
  }

  if (seg[0] === 'vehicles' && seg[2] === 'clip-delete-jobs' && m === 'POST') {
    const vid = seg[1]
    const v = toVehicle(vid)
    if (v.role !== 'owner') err(403, 'forbidden', '观看者无权删除录像')
    requireTicket((body as { actionTicket?: string }).actionTicket)
    const { scope, clipIds } = body as { scope: 'local' | 'cloud'; clipIds: string[] }
    if (!clipIds?.length) err(422, 'invalid', '请选择要删除的片段')
    const job = createJob('clip-delete')
    ;(job as any).vehicleId = vid
    runDeleteJob(job.jobId, vid, clipIds, scope)
    return { jobId: job.jobId } as T
  }

  if (seg[0] === 'vehicles' && seg[2] === 'sentry-intent' && m === 'PUT') {
    const vid = seg[1]
    if (toVehicle(vid).role !== 'owner') err(403, 'forbidden', '观看者无权更改哨兵')
    const { enabled, revision } = body as { enabled: boolean; revision: number }
    sentryState[vid].desired = enabled
    sentryState[vid].revision = (revision ?? 1) + 1
    const job = createJob('sentry')
    ;(job as any).vehicleId = vid
    runSentryJob(job.jobId, vid, enabled, sentryState[vid].revision)
    return { jobId: job.jobId, revision: sentryState[vid].revision } as T
  }

  if (seg[0] === 'vehicles' && seg[2] === 'access' && m === 'GET') {
    const vid = seg[1]
    return {
      ownerId: 'u-demo',
      ownerName: toVehicle(vid).role === 'owner' ? '我' : '车主（家人账号）',
      role: toVehicle(vid).role,
      members: toVehicle(vid).role === 'owner'
        ? [{ userId: 'u-family', displayName: '家人（观看者）', role: 'viewer', grantedAt: now - 20 * 86_400_000 }]
        : [],
    } as T
  }
  if (seg[0] === 'vehicles' && seg[2] === 'access' && seg[3] === 'members' && m === 'DELETE') {
    if (toVehicle(seg[1]).role !== 'owner') err(403, 'forbidden', '仅车主可管理成员')
    requireTicket((body as { actionTicket?: string }).actionTicket)
    return {} as T
  }
  if (seg[0] === 'vehicles' && seg[2] === 'unbind' && m === 'POST') {
    if (toVehicle(seg[1]).role !== 'owner') err(403, 'forbidden', '仅车主可解绑车辆')
    requireTicket((body as { actionTicket?: string }).actionTicket)
    P.boundVehicles = P.boundVehicles.filter((v) => v !== seg[1])
    saveP()
    return {} as T
  }

  if (seg[0] === 'jobs' && m === 'GET') {
    const job = jobs.get(seg[1])
    if (!job) err(404, 'not_found', '作业不存在')
    return job as T
  }

  err(404, 'not_found', `mock 未实现的接口：${m} ${p}`)
}

// —— SSE mock ————————————————————————————
export function subscribeEvents(cb: (e: VehicleEvent) => void): () => void {
  eventListeners.add(cb)
  const timer = setInterval(() => {
    // 演示推送：录制时长与 presence 心跳
    emit({ type: 'recording', vehicleId: 'demo-l6', payload: { state: recording['demo-l6'].state }, at: Date.now() })
  }, 8000)
  return () => {
    eventListeners.delete(cb)
    clearInterval(timer)
  }
}

// 演示辅助：登录页展示当前有效 TOTP（无需真实验证器 App）
export async function demoCurrentCode(): Promise<string | null> {
  if (!P.mfaSecret) return null
  return totpAt(P.mfaSecret, Date.now())
}

// 演示辅助：重置两步验证，回到登录页扫码绑定流程
export function resetMfa() {
  P.mfaEnabled = false
  P.mfaSecret = ''
  P.recoveryCodes = []
  P.usedTotpSteps = []
  saveP()
}
