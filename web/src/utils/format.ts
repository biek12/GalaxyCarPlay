// 展示格式规范：页面功能与设置规格 §2.5 —— 未知显示「—」，不显示 0；GB/MB 一位小数。
import dayjs from 'dayjs'

export function fmtDateTime(ms?: number | null): string {
  if (ms == null) return '—'
  return dayjs(ms).format('YYYY-MM-DD HH:mm:ss')
}

export function fmtTime(ms?: number | null): string {
  if (ms == null) return '—'
  return dayjs(ms).format('HH:mm:ss')
}

export function fmtDate(ms?: number | null): string {
  if (ms == null) return '—'
  return dayjs(ms).format('YYYY-MM-DD')
}

// 时长：mm:ss（≥1 小时用 h:mm:ss）
export function fmtDuration(sec?: number | null): string {
  if (sec == null || !Number.isFinite(sec)) return '—'
  const s = Math.max(0, Math.floor(sec))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = s % 60
  const p = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${p(m)}:${p(ss)}` : `${p(m)}:${p(ss)}`
}

export function fmtBytes(n?: number | null): string {
  if (n == null || !Number.isFinite(n)) return '—'
  if (n >= 1e9) return `${(n / 1e9).toFixed(1)} GB`
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)} MB`
  if (n >= 1e3) return `${(n / 1e3).toFixed(1)} KB`
  return `${n} B`
}

export function fmtSpeed(v?: number | null): string {
  if (v == null) return '—'
  return `${Math.round(v)} km/h`
}

export function fmtNum(v?: number | null, unit = '', digits = 0): string {
  if (v == null) return '—'
  return `${v.toFixed(digits)}${unit}`
}

export function fmtRelTime(ms: number, now = Date.now()): string {
  const diff = now - ms
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

// 可回溯时长估算：配额 ÷ (四路合计码率 ÷ 8)，规格 §5 P6
export function fmtRecallable(quotaBytes: number, mbps: number): string {
  const sec = quotaBytes / ((mbps * 1e6) / 8)
  const h = Math.floor(sec / 3600)
  const m = Math.round((sec % 3600) / 60)
  return h > 0 ? `≈ ${h} 小时 ${m} 分` : `≈ ${m} 分钟`
}
