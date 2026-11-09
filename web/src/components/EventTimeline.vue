<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { TimelineEvent } from '@/api/types'

const props = defineProps<{
  events: TimelineEvent[]
  durationMs: number
  playheadMs: number
  filterKeys: Set<string> // 空集合 = 全部显示
}>()
const emit = defineEmits<{ seek: [ms: number] }>()

const wrap = ref<HTMLElement | null>(null)
const canvas = ref<HTMLCanvasElement | null>(null)

const LANES = [
  { keys: ['brake'], label: '刹车', color: '#e5484d' },
  { keys: ['accel'], label: '油门', color: '#00b96b' },
  { keys: ['lane_assist', 'lane_on', 'lane_off'], label: '横向辅助', color: '#8b5cf6' },
  { keys: ['cruise', 'cruise_on', 'cruise_off'], label: '巡航', color: '#2f7dd1' },
  { keys: ['turn_left', 'turn_right'], label: '瞬时', color: '#e6a700' },
]
const LANE_H = 26
const RULER_H = 18

function laneOf(key: string): number {
  const i = LANES.findIndex((l) => l.keys.includes(key))
  return i < 0 ? LANES.length - 1 : i
}

function visibleEvents(): TimelineEvent[] {
  if (props.filterKeys.size === 0) return props.events
  return props.events.filter((e) => props.filterKeys.has(e.key))
}

function draw() {
  const c = canvas.value
  const box = wrap.value
  if (!c || !box || props.durationMs <= 0) return
  const dpr = window.devicePixelRatio || 1
  const w = box.clientWidth
  const h = RULER_H + LANES.length * LANE_H + 6
  c.width = w * dpr
  c.height = h * dpr
  c.style.height = h + 'px'
  const ctx = c.getContext('2d')!
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  const css = getComputedStyle(document.documentElement)
  const textCol = css.getPropertyValue('--ar-text-2') || '#888'
  const lineCol = css.getPropertyValue('--ar-line') || '#333'
  const dark = document.documentElement.classList.contains('dark')
  ctx.clearRect(0, 0, w, h)
  ctx.fillStyle = dark ? '#14171d' : '#f7f8fa'
  ctx.fillRect(0, 0, w, h)

  const x = (ms: number) => (ms / props.durationMs) * (w - 52) + 52

  // 标尺
  ctx.font = '10px ui-monospace, monospace'
  ctx.fillStyle = textCol
  for (let i = 0; i <= 5; i++) {
    const ms = (props.durationMs * i) / 5
    const px = x(ms)
    ctx.fillText(fmtTick(ms), px - 16, 11)
    ctx.strokeStyle = lineCol
    ctx.beginPath(); ctx.moveTo(px, 14); ctx.lineTo(px, h); ctx.stroke()
  }

  // 轨道
  ctx.font = '11px sans-serif'
  LANES.forEach((lane, i) => {
    const y = RULER_H + i * LANE_H
    ctx.fillStyle = textCol
    ctx.fillText(lane.label, 6, y + 16)
    ctx.strokeStyle = lineCol
    ctx.beginPath(); ctx.moveTo(52, y + LANE_H - 4); ctx.lineTo(w, y + LANE_H - 4); ctx.stroke()
  })

  // 事件块
  for (const e of visibleEvents()) {
    const lane = LANES[laneOf(e.key)]
    const y = RULER_H + laneOf(e.key) * LANE_H
    ctx.fillStyle = lane.color
    if (e.kind === 'continuous' && e.endOffsetMs != null) {
      const x0 = x(e.startOffsetMs)
      const x1 = Math.max(x0 + 3, x(e.endOffsetMs))
      const bh = 12
      ctx.beginPath()
      if (typeof ctx.roundRect === 'function') ctx.roundRect(x0, y + (LANE_H - bh) / 2 - 4, x1 - x0, bh, 3)
      else ctx.rect(x0, y + (LANE_H - bh) / 2 - 4, x1 - x0, bh)
      ctx.fill()
    } else {
      // 瞬时：菱形
      const px = x(e.startOffsetMs)
      const cy = y + LANE_H / 2 - 4
      ctx.save()
      ctx.translate(px, cy)
      ctx.rotate(Math.PI / 4)
      ctx.fillRect(-4, -4, 8, 8)
      ctx.restore()
    }
  }

  // 游标
  const cx = x(Math.min(props.playheadMs, props.durationMs))
  ctx.strokeStyle = '#00b96b'
  ctx.lineWidth = 2
  ctx.beginPath(); ctx.moveTo(cx, 4); ctx.lineTo(cx, h); ctx.stroke()
  ctx.fillStyle = '#00b96b'
  ctx.beginPath(); ctx.moveTo(cx - 5, 2); ctx.lineTo(cx + 5, 2); ctx.lineTo(cx, 9); ctx.closePath(); ctx.fill()
}

function fmtTick(ms: number): string {
  const s = ms / 1000
  const m = Math.floor(s / 60)
  const ss = Math.floor(s % 60)
  return `${String(m).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
}

onMounted(draw)
const redraw = () => draw()
watch(() => [props.playheadMs, props.events, props.durationMs, props.filterKeys], redraw, { deep: true })
window.addEventListener('resize', redraw)
onBeforeUnmount(() => window.removeEventListener('resize', redraw))

function onClick(ev: MouseEvent) {
  const c = canvas.value!
  const rect = c.getBoundingClientRect()
  const px = ev.clientX - rect.left
  const ms = ((px - 52) / (rect.width - 52)) * props.durationMs
  emit('seek', Math.max(0, Math.min(props.durationMs, ms)))
}
</script>

<template>
  <div ref="wrap" style="width: 100%">
    <canvas class="timeline-canvas" @click="onClick" />
  </div>
</template>
