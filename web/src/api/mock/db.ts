// mock 数据生成：车辆、片段、遥测样本与事件。
// 全部为显式 mock，生产接口接入后整体移除（见 README）。
import type { Clip, ClipAvailability, ClipChannel, Direction, TelemetrySample, TimelineEvent } from '@/api/types'
import { DIRECTIONS } from '@/api/types'

export function mulberry32(seed: number) {
  let a = seed >>> 0
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export function hashStr(s: string): number {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

const MIN = 60_000

export interface ClipRow extends Clip {
  seed: number
  playbackOffsetsMs: Record<Direction, number>
}

// —— 遥测与事件生成 ——————————————————————————————

interface DriveScript {
  events: TimelineEvent[]
  genSample(tMs: number, intervalMs: number): Omit<TelemetrySample, 't' | 'q'>
  missingRanges: [number, number][]
  staleRanges: [number, number][]
}

function buildDriveScript(seed: number, durationMs: number, crossMidnight: boolean): DriveScript {
  const rng = mulberry32(seed)
  const events: TimelineEvent[] = []
  const missingRanges: [number, number][] = []
  const staleRanges: [number, number][] = []
  let eid = 0
  const patterns: string[] = []
  const pool = ['calm', 'brake', 'throttle', 'cruise', 'lane', 'mixed', 'brake', 'calm']
  const n = 1 + Math.floor(rng() * 3)
  for (let i = 0; i < n; i++) patterns.push(pool[Math.floor(rng() * pool.length)])
  const has = (k: string) => patterns.includes(k)

  // 缺失窗口（数据缺失演示）：约 2-4s
  const missStart = durationMs * (0.3 + rng() * 0.4)
  missingRanges.push([missStart, missStart + 2000 + rng() * 2000])
  // 过期窗口
  const staleStart = durationMs * (0.1 + rng() * 0.2)
  staleRanges.push([staleStart, staleStart + 3000])

  // 力度脚本：各时刻的目标刹车/油门力度（含 100→80→20→0 阶梯、短踩）
  interface ForceSeg { t0: number; t1: number; peak: number; kind: 'brake' | 'throttle' }
  const segs: ForceSeg[] = []
  let cursor = 6000 + rng() * 6000
  const pushForce = (kind: 'brake' | 'throttle', count: number) => {
    for (let i = 0; i < count; i++) {
      if (cursor > durationMs - 8000) break
      const dur = rng() < 0.3 ? 400 + rng() * 300 : 2000 + rng() * 3000 // 含短踩 <1s
      const peak = kind === 'brake' ? 30 + rng() * 70 : 25 + rng() * 60
      segs.push({ t0: cursor, t1: cursor + dur, peak, kind })
      cursor += dur + 4000 + rng() * 8000
    }
  }
  if (has('brake') || has('mixed')) pushForce('brake', 1 + Math.floor(rng() * 2))
  if (has('throttle') || has('mixed')) pushForce('throttle', 1 + Math.floor(rng() * 2))

  // 巡航
  let cruiseOn = false
  let cruiseSet: number | null = null
  if (has('cruise') || has('mixed')) {
    const t0 = 8000 + rng() * (durationMs * 0.35)
    if (t0 < durationMs - 10000) {
      cruiseOn = true
      cruiseSet = Math.round((50 + rng() * 30) / 5) * 5
      events.push({ eventId: `e${eid++}`, key: 'cruise_on', startOffsetMs: t0, endOffsetMs: null, kind: 'instant', sourceQuality: 'good', ruleVersion: 3 })
      events.push({ eventId: `e${eid++}`, key: 'cruise', startOffsetMs: t0, endOffsetMs: Math.min(durationMs - 4000, t0 + 15000 + rng() * 15000), kind: 'continuous', sourceQuality: 'good', ruleVersion: 3 })
      const tOff = t0 + 16000 + rng() * 12000
      if (tOff < durationMs - 3000) {
        events.push({ eventId: `e${eid++}`, key: 'cruise_off', startOffsetMs: tOff, endOffsetMs: null, kind: 'instant', sourceQuality: 'good', ruleVersion: 3 })
        events[events.length - 2].endOffsetMs = tOff
      }
    }
  }

  // 横向辅助
  let lane = false
  if (has('lane') || has('mixed')) {
    const t0 = 5000 + rng() * (durationMs * 0.4)
    if (t0 < durationMs - 8000) {
      lane = true
      events.push({ eventId: `e${eid++}`, key: 'lane_on', startOffsetMs: t0, endOffsetMs: null, kind: 'instant', sourceQuality: 'good', ruleVersion: 3 })
      const tOff = t0 + 12000 + rng() * 12000
      if (tOff < durationMs - 3000) {
        events.push({ eventId: `e${eid++}`, key: 'lane_off', startOffsetMs: tOff, endOffsetMs: null, kind: 'instant', sourceQuality: 'good', ruleVersion: 3 })
        events.push({ eventId: `e${eid++}`, key: 'lane_assist', startOffsetMs: t0, endOffsetMs: tOff, kind: 'continuous', sourceQuality: 'good', ruleVersion: 3 })
      }
    }
  }

  // 刹车/油门事件（由力度脚本推导，持续型）
  for (const s of segs) {
    if (s.kind === 'brake') {
      events.push({ eventId: `e${eid++}`, key: 'brake', startOffsetMs: s.t0, endOffsetMs: s.t1, kind: 'continuous', sourceQuality: 'good', ruleVersion: 3 })
    } else {
      events.push({ eventId: `e${eid++}`, key: 'accel', startOffsetMs: s.t0, endOffsetMs: s.t1, kind: 'continuous', sourceQuality: 'good', ruleVersion: 3 })
    }
  }

  // 转向灯
  const turnAt = 15000 + rng() * (durationMs - 20000)
  const turnDir: 'left' | 'right' = rng() < 0.5 ? 'left' : 'right'
  let turn: 'off' | 'left' | 'right' | 'both' = 'off'
  if (turnAt > 0 && turnAt < durationMs - 4000) {
    turn = turnDir
    events.push({ eventId: `e${eid++}`, key: `turn_${turnDir}`, startOffsetMs: turnAt, endOffsetMs: null, kind: 'instant', sourceQuality: 'good', ruleVersion: 3 })
  }

  const baseSpeed = 38 + rng() * 26

  function forceAt(tMs: number, kind: 'brake' | 'throttle'): number | null {
    // 阶梯力度：100→80→20→0（演示力度条与短踩保留）
    let v = 0
    for (const s of segs) {
      if (s.kind !== kind) continue
      if (tMs < s.t0 || tMs > s.t1 + 600) continue
      const dur = s.t1 - s.t0
      const p = (tMs - s.t0) / Math.max(400, dur)
      if (p <= 0.25) v = Math.max(v, s.peak)
      else if (p <= 0.5) v = Math.max(v, s.peak * 0.8)
      else if (p <= 0.85) v = Math.max(v, s.peak * 0.2)
      else v = 0
    }
    return v
  }

  return {
    events,
    missingRanges,
    staleRanges,
    genSample(tMs: number, intervalMs: number) {
      const brake = forceAt(tMs, 'brake')
      const throttle = forceAt(tMs, 'throttle')
      const speed = Math.max(0, baseSpeed + Math.sin(tMs / 9000) * 8 - (brake ?? 0) * 0.25 + (throttle ?? 0) * 0.08)
      const nightBias = crossMidnight ? 1 : 0
      return {
        speed: Math.round(speed * 10) / 10,
        gear: 'D' as const,
        rpm: Math.round(900 + speed * 22 + (throttle ?? 0) * 30 + nightBias * 40),
        steer: Math.round(Math.sin(tMs / 5200) * 60 + Math.sin(tMs / 1300) * 8),
        throttle,
        brake,
        turn,
        laneAssist: lane,
        cruiseOn,
        cruiseSet,
        beam: nightBias ? 'low' : ('auto' as unknown as 'off'),
        fog: false,
        autoHold: tMs > durationMs - 8000,
        parkBrake: false,
        fuelPct: 62 - (tMs / durationMs) * 2,
        evRangeKm: 412 - Math.round(tMs / durationMs * 6),
        odoKm: 38241 + tMs / durationMs,
        tempInC: 23.5,
        tempOutC: 18.2,
      }
    },
  }
}

const KEYS_GAP = 2200 // 缺失窗口外的样本间隔补偿

export function generateTelemetry(seed: string, durationMs: number, intervalMs: number, crossMidnight: boolean) {
  const s = hashStr(seed)
  const script = buildDriveScript(s, durationMs, crossMidnight)
  const samples: TelemetrySample[] = []
  for (let t = 0; t <= durationMs; t += intervalMs) {
    const missing = script.missingRanges.some(([a, b]) => t >= a && t < b)
    const stale = !missing && script.staleRanges.some(([a, b]) => t >= a && t < b)
    if (missing) {
      samples.push({ t, q: 'missing' })
      continue
    }
    const v = script.genSample(t, intervalMs)
    // 数据过期：值沿用但标记过期（回放必须显示「过期」，不得冒充实时值）
    if (stale) samples.push({ t, q: 'stale', ...v, speed: null })
    else samples.push({ t, q: 'good', ...v })
  }
  // 跨午夜片段：末尾追加 P 挡驻车
  if (crossMidnight && samples.length) {
    const last = samples[samples.length - 1]
    if (last.q !== 'missing') {
      last.gear = 'P'
      last.speed = 0
      last.parkBrake = true
      last.autoHold = true
    }
  }
  return { samples, events: script.events.sort((a, b) => a.startOffsetMs - b.startOffsetMs) }
}

// —— 片段库 ——————————————————————————————

export interface VehicleRow {
  vehicleId: string
  name: string
  model: string
  plateMasked: string
  role: 'owner' | 'viewer'
  online: boolean
  sleeping: boolean
}

const DAY = 86_400_000

function mkChannels(rng: () => number, clipId: string): ClipChannel[] {
  return DIRECTIONS.map((direction) => {
    let status: ClipChannel['status'] = 'ok'
    if (clipId.endsWith('-miss-rear') && direction === 'rear') status = 'missing'
    if (clipId.endsWith('-miss-lr') && (direction === 'rear' || direction === 'right')) status = 'missing'
    if (clipId.endsWith('-partial') && direction === 'front') status = 'partial'
    const mediaStartOffsetMs = Math.round((rng() * 400 - 80)) // 各路首帧起始偏差（对齐演示）
    const durationMs = Math.round(rng() * 200)
    return { direction, status, mediaStartOffsetMs: status === 'missing' ? 0 : mediaStartOffsetMs, durationMs: status === 'missing' ? 0 : 60_000 + durationMs, projectionVersion: 'opencv-fisheye4-v0' }
  })
}

function buildClips(vehicleId: string, daysBack: number, perDay: [number, number], cloudOnly = false): ClipRow[] {
  const rng = mulberry32(hashStr(vehicleId + '-clips'))
  const clips: ClipRow[] = []
  const now = Date.now()
  const base = new Date(now)
  base.setHours(0, 0, 0, 0)
  for (let d = daysBack; d >= 0; d--) {
    if (d === 0 && vehicleId !== 'demo-l6') continue
    const dayStart = base.getTime() - d * DAY
    const count = perDay[0] + Math.floor(rng() * (perDay[1] - perDay[0] + 1))
    let sessionId = `${vehicleId}-s${d}-1`
    let prevEnd = 0
    for (let i = 0; i < count; i++) {
      let startAt: number
      if (prevEnd && rng() < 0.45 && prevEnd - dayStart > 0) {
        // 续段（同会话连续分段）
        startAt = prevEnd
      } else {
        sessionId = `${vehicleId}-s${d}-${i + 1}`
        const hour = 7 + Math.floor(rng() * 14)
        const minute = Math.floor(rng() * 60)
        startAt = dayStart + hour * 3_600_000 + minute * MIN
        if (d === 3 && i === count - 2) startAt = dayStart + 23 * 3_600_000 + 59 * MIN + 20_000 // 跨午夜
        if (startAt > now - MIN) break
      }
      const endAt = startAt + 60_000
      if (endAt > now) break
      prevEnd = endAt
      const clipId = `${vehicleId}-c${d}-${i}`
      const r = rng()
      let availability: ClipAvailability = 'ok'
      if (clipId.endsWith('c3-' + (count - 1))) availability = 'partial'
      else if (clipId.endsWith('c2-0') && d === 5) availability = 'recoverable'
      else if (clipId.endsWith('c4-1') && d === 2) availability = 'missing'
      else if (r < 0.04) availability = 'overwritten'
      const clip: ClipRow = {
        clipId,
        sessionId,
        startAt,
        endAt,
        source: cloudOnly ? 'cloud' : r < 0.12 ? 'cloud' : 'local',
        availability,
        locked: clipId.endsWith('c5-2') || clipId.endsWith('c1-0'),
        watched: rng() > 0.55,
        eventCount: 0,
        bytes: 110 * 1e6 + Math.floor(rng() * 30 * 1e6),
        channels: mkChannels(rng, clipId),
        telemetry: { schema: 'vehicle-feed-v4', actualIntervalMs: d === 1 && i === 0 ? 250 : 500, availability: 'full' },
        seed: hashStr(clipId),
        playbackOffsetsMs: { front: 0, rear: 0, left: 0, right: 0 },
      }
      if (availability === 'missing') clip.telemetry.availability = 'none'
      clips.push(clip)
    }
  }
  // 事件计数由遥测脚本回填（延迟生成，此处按 seed 预估以保证筛选可用）
  for (const c of clips) c.eventCount = estimateEvents(c.seed)
  return clips.sort((a, b) => b.startAt - a.startAt)
}

function estimateEvents(seed: number): number {
  const rng = mulberry32(seed ^ 0x9e3779b9)
  const r = rng()
  if (r < 0.35) return 0
  return 2 + Math.floor(rng() * 8)
}

export function buildDb() {
  const now = Date.now()
  return {
    createdAt: now,
    vehicles: [
      { vehicleId: 'demo-l6', name: '银河 L6', model: '2023 Max · FlymeAuto 2.3.1', plateMasked: '浙A·D**L6', role: 'owner' as const, online: true, sleeping: false },
      { vehicleId: 'demo-l7', name: '银河 L7', model: '2024 Max', plateMasked: '浙A·E**L7', role: 'owner' as const, online: false, sleeping: true },
      { vehicleId: 'demo-share', name: 'L6（家人授权）', model: '2023 Max', plateMasked: '浙B·F**K2', role: 'viewer' as const, online: true, sleeping: false },
    ],
    clips: {
      'demo-l6': buildClips('demo-l6', 6, [3, 6]),
      'demo-l7': buildClips('demo-l7', 2, [4, 8], true),
      'demo-share': buildClips('demo-share', 2, [2, 4]),
    } as Record<string, ClipRow[]>,
  }
}
