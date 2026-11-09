<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '@/api/client'
import { Spin } from 'ant-design-vue'
import { DIR_LABEL, type Direction } from '@/api/types'
import { drawFrame, drawPlaceholder } from '@/api/mock/video'

const props = withDefaults(defineProps<{
  direction: Direction
  status: 'streaming' | 'preparing' | 'missing' | 'occupied' | 'stalled'
  flat?: boolean
  stillT?: number // 静态帧（回放）：传入则单帧绘制
  showClock?: boolean
  mediaUrl?: string
  playing?: boolean
  speed?: number
  stream?: MediaStream
}>(), { flat: false, stillT: undefined, showClock: true })

const emit = defineEmits<{ 'first-frame': []; stalled: [] }>()
const canvasRef = ref<HTMLCanvasElement | null>(null)
let raf = 0
const videoRef = ref<HTMLVideoElement | null>(null)
const effectiveStatus = computed(() => !api.mock && !props.mediaUrl && !props.stream ? 'missing' : props.status)
function syncVideo() {
  const video = videoRef.value
  if (api.mock || !video) return
  video.playbackRate = props.speed ?? 1
  if (props.stream && video.srcObject !== props.stream) video.srcObject = props.stream
  const target = props.stillT
  if (target != null && target >= 0 && Number.isFinite(target) && Number.isFinite(video.duration) && Math.abs(video.currentTime - target) > .15) video.currentTime = Math.min(target, video.duration)
  if (props.stream || (props.playing && effectiveStatus.value === 'streaming')) video.play().catch(() => {})
  else video.pause()
}
watch(() => [props.stillT, props.playing, props.speed, props.mediaUrl, props.stream, props.status], syncVideo)


function drawStill() {
  const ctx = canvasRef.value?.getContext('2d')
  if (!api.mock || !ctx) return
  if (props.stillT == null) return
  if (props.status === 'missing') { drawPlaceholder(ctx, '缺路 · 无该方向画面'); return }
  if (props.status === 'occupied') { drawPlaceholder(ctx, '等待原厂释放'); return }
  drawFrame(ctx, props.direction, props.stillT, { flat: props.flat })
}

function loop() {
  const ctx = canvasRef.value?.getContext('2d')
  if (api.mock && ctx && props.status === 'streaming') {
    drawFrame(ctx, props.direction, Date.now() / 1000 % 3600, { flat: props.flat })
  }
  raf = requestAnimationFrame(loop)
}

onMounted(() => {
  if (!api.mock) { syncVideo(); return }
  if (props.stillT == null) raf = requestAnimationFrame(loop)
  else drawStill()
})
onBeforeUnmount(() => { cancelAnimationFrame(raf); if (videoRef.value) { videoRef.value.pause(); videoRef.value.srcObject = null; videoRef.value.removeAttribute('src'); videoRef.value.load() } })
watch(() => [props.stillT, props.status, props.flat], () => {
  if (props.stillT != null) drawStill()
})

const maskText: Record<string, string> = {
  preparing: '等待摄像头 · 首帧未到',
  missing: '缺路 · 该方向无画面',
  occupied: '等待原厂释放',
  stalled: '断流 · 已遮蔽旧画面',
}
</script>

<template>
  <div class="cam-tile">
    <span class="dir-tag">{{ DIR_LABEL[direction] }}</span>
    <canvas v-if="api.mock" ref="canvasRef" width="480" height="270" />
    <video v-else ref="videoRef" :src="mediaUrl" muted playsinline preload="metadata" @loadedmetadata="syncVideo" @playing="emit('first-frame')" @waiting="emit('stalled')" @stalled="emit('stalled')" />
    <div v-if="effectiveStatus !== 'streaming'" class="status-mask">
      <Spin v-if="effectiveStatus === 'preparing'" size="small" />
      <span>{{ !api.mock && !mediaUrl && !stream ? '画面尚未接入或原片未上传' : maskText[effectiveStatus] }}</span>
    </div>
  </div>
</template>
