// mock 四路画面渲染器：实时与回放共用同一时间驱动画法，
// 保证回放 seek/倍速下四路画面与时间轴一致；真实流接入后由播放适配器替换。
import type { Direction } from '@/api/types'

export interface FrameOpts {
  flat?: boolean // 平面模式（示意）
  label?: string // 额外水印
}

function skyGround(ctx: CanvasRenderingContext2D, w: number, h: number, night: boolean) {
  const g = ctx.createLinearGradient(0, 0, 0, h)
  if (night) {
    g.addColorStop(0, '#0b1026')
    g.addColorStop(0.55, '#131a33')
    g.addColorStop(0.56, '#141821')
    g.addColorStop(1, '#1c212b')
  } else {
    g.addColorStop(0, '#7fb3e6')
    g.addColorStop(0.55, '#c8dcf0')
    g.addColorStop(0.56, '#5a6470')
    g.addColorStop(1, '#3a4148')
  }
  ctx.fillStyle = g
  ctx.fillRect(0, 0, w, h)
}

function vignette(ctx: CanvasRenderingContext2D, w: number, h: number) {
  const g = ctx.createRadialGradient(w / 2, h / 2, Math.min(w, h) * 0.34, w / 2, h / 2, Math.max(w, h) * 0.62)
  g.addColorStop(0, 'rgba(0,0,0,0)')
  g.addColorStop(1, 'rgba(0,0,0,0.55)')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, w, h)
}

function hash(n: number): number {
  const x = Math.sin(n * 127.1) * 43758.5453
  return x - Math.floor(x)
}

function drawFront(ctx: CanvasRenderingContext2D, w: number, h: number, t: number, night: boolean) {
  skyGround(ctx, w, h, night)
  const horizon = h * 0.56
  // 远处车辆（周期性出现）
  const cycle = 9
  const ph = (t % cycle) / cycle
  if (ph > 0.35) {
    const p = (ph - 0.35) / 0.65
    const carScale = 0.08 + p * p * 0.9
    const cw = w * 0.22 * carScale
    const ch = h * 0.13 * carScale
    const cx = w / 2 - cw / 2 + Math.sin(t * 0.7) * w * 0.02
    const cy = horizon + (h - horizon) * p * 0.72
    ctx.fillStyle = night ? '#3d4658' : '#8c94a0'
    ctx.fillRect(cx, cy - ch, cw, ch)
    ctx.fillStyle = night ? '#ffd766' : '#c03028'
    ctx.fillRect(cx + cw * 0.12, cy - ch * 0.55, cw * 0.76, ch * 0.18)
  }
  // 路面
  ctx.fillStyle = night ? '#20242c' : '#565e66'
  ctx.beginPath()
  ctx.moveTo(w * 0.5 - w * 0.06, horizon)
  ctx.lineTo(w * 0.5 + w * 0.06, horizon)
  ctx.lineTo(w * 1.15, h)
  ctx.lineTo(-w * 0.15, h)
  ctx.closePath()
  ctx.fill()
  // 车道虚线（随 t 移动）
  ctx.strokeStyle = night ? '#d9dde3' : '#f2f2f2'
  ctx.lineWidth = Math.max(2, w * 0.02)
  for (let i = 0; i < 6; i++) {
    const p = ((t * 0.5 + i * 0.35) % 1.4) / 1.4
    if (p > 1) continue
    const y = horizon + (h - horizon) * p * p
    const x0 = w / 2 + (w * 0.02) * (1 + p * 5)
    const x1 = w / 2 - (w * 0.02) * (1 + p * 5)
    const len = h * 0.03 * (1 + p * 6)
    ctx.beginPath()
    ctx.moveTo(x0, y - len / 2)
    ctx.lineTo(x1, y + len / 2)
    ctx.stroke()
  }
  // 两侧路缘
  ctx.fillStyle = night ? '#2c313a' : '#6a7078'
  ctx.fillRect(w * 0.06, horizon, w * 0.12, h - horizon)
  ctx.fillRect(w * 0.82, horizon, w * 0.12, h - horizon)
}

function drawRear(ctx: CanvasRenderingContext2D, w: number, h: number, t: number, night: boolean) {
  skyGround(ctx, w, h, night)
  const horizon = h * 0.5
  // 车位线（泊车场景）
  ctx.strokeStyle = night ? '#7fd7a8' : '#e8f5ee'
  ctx.lineWidth = Math.max(2, w * 0.012)
  for (let i = 0; i < 3; i++) {
    const x = w * (0.18 + i * 0.3)
    ctx.beginPath()
    ctx.moveTo(x, horizon)
    ctx.lineTo(x * 0.92 - w * 0.05, h)
    ctx.stroke()
  }
  // 缓慢逼近的障碍柱
  const p = ((t * 0.12) % 1)
  const bw = w * (0.16 + p * 0.25)
  const bh = h * (0.1 + p * 0.28)
  ctx.fillStyle = night ? '#454c58' : '#9aa1ab'
  ctx.fillRect(w / 2 - bw / 2, horizon + (h - horizon) * (0.25 + p * 0.55) - bh, bw, bh)
  ctx.fillStyle = night ? '#ffb020' : '#d8b400'
  ctx.fillRect(w / 2 - bw / 2, horizon + (h - horizon) * (0.25 + p * 0.55) - bh, bw, bh * 0.16)
}

function drawSide(ctx: CanvasRenderingContext2D, w: number, h: number, t: number, night: boolean, dir: 'left' | 'right') {
  skyGround(ctx, w, h, night)
  const horizon = h * 0.52
  // 路缘延伸线
  ctx.fillStyle = night ? '#20242c' : '#565e66'
  ctx.fillRect(0, horizon + h * 0.12, w, h)
  // 路灯/树沿行进方向掠过（left 向右掠、right 向左掠）
  const speed = dir === 'left' ? 1 : -1
  for (let i = 0; i < 5; i++) {
    const ph = ((t * 0.45 * speed + i * 0.2) % 1 + 1) % 1
    const x = dir === 'left' ? ph * w * 1.2 - w * 0.1 : w * 1.1 - ph * w * 1.2
    const scale = 0.5 + ph * 1.6
    const ph2 = h * 0.3 * scale
    ctx.fillStyle = night ? '#2b3038' : '#5c6470'
    ctx.fillRect(x, horizon - ph2 + h * 0.05, w * 0.02 * scale, ph2 + h * 0.1)
    ctx.fillStyle = night ? '#37404d' : '#4f7a52'
    ctx.beginPath()
    ctx.ellipse(x + w * 0.01 * scale, horizon - ph2 + h * 0.05, w * 0.06 * scale, h * 0.06 * scale, 0, 0, Math.PI * 2)
    ctx.fill()
  }
  // 护栏
  ctx.strokeStyle = night ? '#4a5160' : '#b7bec8'
  ctx.lineWidth = Math.max(1.5, h * 0.012)
  ctx.beginPath()
  ctx.moveTo(0, horizon + h * 0.2)
  ctx.lineTo(w, horizon + h * 0.2)
  ctx.stroke()
}

// 主入口：绘制某方向在 t 秒时刻的 mock 画面
export function drawFrame(ctx: CanvasRenderingContext2D, dir: Direction, tSec: number, opts: FrameOpts = {}) {
  const w = ctx.canvas.width
  const h = ctx.canvas.height
  const night = !!opts.label?.includes('night')
  ctx.save()
  drawFrontRearSide(ctx, dir, tSec, night)
  if (!opts.flat) vignette(ctx, w, h)
  // 鱼眼时间水印（画面内嵌时间便于核对同步）
  ctx.fillStyle = 'rgba(255,255,255,0.82)'
  ctx.font = `${Math.round(h * 0.07)}px ui-monospace, monospace`
  ctx.textAlign = 'right'
  const tt = Math.max(0, tSec)
  const mm = String(Math.floor(tt / 60)).padStart(2, '0')
  const ss = (tt % 60).toFixed(1).padStart(4, '0')
  ctx.fillText(`${mm}:${ss}`, w - h * 0.05, h * 0.11)
  ctx.textAlign = 'left'
  ctx.fillStyle = 'rgba(255,255,255,0.4)'
  ctx.font = `${Math.round(h * 0.05)}px sans-serif`
  ctx.fillText(opts.flat ? 'MOCK·平面(示意)' : 'MOCK·鱼眼', h * 0.04, h * 0.11)
  ctx.restore()
}

function drawFrontRearSide(ctx: CanvasRenderingContext2D, dir: Direction, t: number, night: boolean) {
  const w = ctx.canvas.width
  const h = ctx.canvas.height
  if (dir === 'front') drawFront(ctx, w, h, t, night)
  else if (dir === 'rear') drawRear(ctx, w, h, t, night)
  else drawSide(ctx, w, h, t, night, dir)
}

// 占位帧：缺路/等待/占用等状态由瓦片组件用 CSS 呈现，不借其他方向画面
export function drawPlaceholder(ctx: CanvasRenderingContext2D, text: string) {
  const w = ctx.canvas.width
  const h = ctx.canvas.height
  ctx.fillStyle = '#11151b'
  ctx.fillRect(0, 0, w, h)
  ctx.fillStyle = 'rgba(255,255,255,0.35)'
  ctx.font = `${Math.round(h * 0.09)}px sans-serif`
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(text, w / 2, h / 2)
  ctx.textBaseline = 'alphabetic'
}

// 缩略图：2×2 四向拼接（回放库卡片用），带缓存
const thumbCache = new Map<string, string>()

export function clipThumb(clipId: string, seedDirs: Direction[], atOffsetsSec: number[], flat = false): string {
  const key = `${clipId}|${flat}`
  const hit = thumbCache.get(key)
  if (hit) return hit
  const c = document.createElement('canvas')
  c.width = 256
  c.height = 144
  const ctx = c.getContext('2d')!
  const half = { w: 128, h: 72 }
  seedDirs.forEach((dir, i) => {
    const t = atOffsetsSec[i] ?? 0
    const off = hash(clipId.charCodeAt(i % clipId.length) + i) * 2.5
    ctx.save()
    ctx.translate((i % 2) * half.w, Math.floor(i / 2) * half.h)
    ctx.beginPath()
    ctx.rect(0, 0, half.w, half.h)
    ctx.clip()
    drawFrame(ctx, dir, t + off, { flat })
    ctx.restore()
  })
  const url = c.toDataURL('image/jpeg', 0.72)
  thumbCache.set(key, url)
  return url
}
