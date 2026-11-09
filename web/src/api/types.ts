// 与后端契约对应的 DTO 类型（契约见 docs/AutoRecord/在线查看网页需求与Agent交接-20261105.md §6）
// 时间统一 Unix 毫秒；片段区间 [from, until)。

export type Direction = 'front' | 'rear' | 'left' | 'right'

export const DIRECTIONS: Direction[] = ['front', 'rear', 'left', 'right']

export const DIR_LABEL: Record<Direction, string> = {
  front: '前',
  rear: '后',
  left: '左',
  right: '右',
}

export type PresenceState = 'online' | 'sleeping' | 'waking' | 'offline' | 'unknown' | 'fault'

export type VehicleRole = 'owner' | 'viewer'

export interface Vehicle {
  vehicleId: string
  name: string
  model: string
  plateMasked: string
  basicInfo?: { model: string; modelSource: string; manufacturer: string; deviceModel: string; androidVersion: string; sdk: number; serviceVersion: string }
  role: VehicleRole
  presence: { state: PresenceState; lastSeenAt: number }
  capabilities: {
    remoteWake: { supported: boolean; reason?: string }
    flatView: Record<Direction, boolean>
  }
  recording: { state: 'recording' | 'idle' | 'unknown'; startedAt?: number; observedAt: number }
  storage: { volume: string; usedBytes: number; quotaBytes: number; freeBytes: number }
  sentry: { desired: boolean; actual: 'unknown' | 'disarmed' | 'armed_standby' | 'armed_recording' | 'restricted' | 'failed'; revision: number }
}

export interface BindingTicket {
  bindingId: string
  status: 'pending_device_confirmation' | 'confirmed' | 'rejected' | 'expired'
  vehicleId?: string
  reason?: string
}

export interface ClipChannel {
  direction: Direction
  status: 'ok' | 'missing' | 'partial'
  mediaStartOffsetMs: number
  durationMs: number
  mediaUrl?: string
  projectionVersion?: string
}

export type ClipAvailability = 'ok' | 'partial' | 'recoverable' | 'missing' | 'overwritten'

export interface Clip {
  clipId: string
  sessionId: string
  startAt: number
  endAt: number
  source: 'local' | 'cloud'
  availability: ClipAvailability
  locked: boolean
  watched: boolean
  eventCount: number
  bytes: number
  channels: ClipChannel[]
  telemetry: { schema: string; actualIntervalMs: number; availability: 'full' | 'gaps' | 'none' }
}

export interface ClipListResult {
  items: Clip[]
  nextCursor: string | null
  indexReady: boolean
}

export type EventKind = 'continuous' | 'instant'

export interface TimelineEvent {
  eventId: string
  key: string
  startOffsetMs: number
  endOffsetMs: number | null
  kind: EventKind
  sourceQuality: 'good' | 'estimated'
  ruleVersion: number
}

export interface TelemetrySample {
  t: number // 片段内偏移 ms
  q: 'good' | 'stale' | 'missing'
  speed?: number | null // km/h
  gear?: 'P' | 'R' | 'N' | 'D' | null
  rpm?: number | null
  steer?: number | null // °，带符号
  throttle?: number | null // 0-100 力度
  brake?: number | null // 0-100 力度
  turn?: 'off' | 'left' | 'right' | 'both' | null
  laneAssist?: boolean | null
  cruiseOn?: boolean | null
  cruiseSet?: number | null
  beam?: 'off' | 'low' | 'high' | null
  fog?: boolean | null
  autoHold?: boolean | null
  parkBrake?: boolean | null
  fuelPct?: number | null
  evRangeKm?: number | null
  odoKm?: number | null
  tempInC?: number | null
  tempOutC?: number | null
}

export interface TelemetryResult {
  schema: string
  actualIntervalMs: number
  clockBiasUs: number
  samples: TelemetrySample[]
  events: TimelineEvent[]
  sessionClips: { clipId: string; startAt: number; endAt: number }[]
}

export interface JobItem {
  ref: string
  state: 'success' | 'skipped' | 'failed'
  reason?: string
}

export interface Job {
  jobId: string
  type: 'clip-delete' | 'clip-prepare' | 'wake' | 'sentry' | 'unbind'
  state: 'queued' | 'dispatched' | 'waking' | 'device_online' | 'ready' | 'running' | 'success' | 'partial_success' | 'failed' | 'expired' | 'cancelled'
  progress?: { done: number; total: number }
  items?: JobItem[]
  reason?: string
  updatedAt: number
  createdAt: number
}

export interface MeInfo {
  userId: string
  displayName: string
  phoneMasked: string
  mfaEnabled: boolean
  createdAt: number
}

export interface SessionInfo {
  id: string
  device: string
  ipMasked: string
  lastActiveAt: number
  current: boolean
}

export interface LiveSession {
  iceServers: RTCIceServer[]
  sessionId: string
  expiresAt: number
  channels: { direction: Direction; status: 'ok' | 'occupied' | 'unavailable' }[]
}

export interface VehicleEvent {
  type: 'presence' | 'recording' | 'sentry' | 'job'
  vehicleId?: string
  jobId?: string
  payload: Record<string, unknown>
  at: number
}
