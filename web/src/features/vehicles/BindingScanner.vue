<script setup lang="ts">
import { onBeforeUnmount, ref, watch, nextTick } from 'vue'
import { Alert, Button, Upload } from 'ant-design-vue'
import jsQR from 'jsqr'
import { parseBindingCode } from './bindingCode'
const props = defineProps<{ active: boolean }>()
const emit = defineEmits<{ code: [string]; close: [] }>()
const video = ref<HTMLVideoElement | null>(null)
const error = ref('')
let stream: MediaStream | undefined
let timer = 0
let generation = 0
function stop() {
  generation++; clearTimeout(timer); stream?.getTracks().forEach(t => t.stop()); stream = undefined
  if (video.value) video.value.srcObject = null
}
function decode(source: CanvasImageSource, width: number, height: number) {
  const canvas = document.createElement('canvas')
  canvas.width = Math.min(width, 960); canvas.height = Math.round(height * canvas.width / width)
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  ctx.drawImage(source, 0, 0, canvas.width, canvas.height)
  const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height)
  const qr = jsQR(pixels.data, pixels.width, pixels.height, { inversionAttempts: 'dontInvert' })
  if (!qr) return false
  const code = parseBindingCode(qr.data)
  stop(); emit('code', code); return true
}
async function start() {
  stop(); error.value = ''; const ticket = generation
  try {
    const next = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false })
    if (ticket !== generation || !props.active) { next.getTracks().forEach(t => t.stop()); return }
    stream = next; await nextTick()
    if (!video.value) { stop(); return }
    video.value.srcObject = next; await video.value.play()
    const scan = () => {
      if (ticket !== generation || !props.active) return
      const v = video.value
      try { if (v && v.readyState >= 2 && decode(v, v.videoWidth, v.videoHeight)) return } catch (e: any) { error.value = e.message }
      timer = window.setTimeout(scan, 220)
    }
    scan()
  } catch { error.value = '无法打开摄像头，请允许相机权限，或选择二维码图片识别。' }
}
async function image(file: File) {
  const url = URL.createObjectURL(file)
  try {
    const img = new Image(); img.src = url; await img.decode()
    if (!decode(img, img.naturalWidth, img.naturalHeight)) error.value = '没有识别到二维码，请使用车端显示的完整二维码。'
  } catch (e: any) { error.value = e.message ?? '二维码读取失败' } finally { URL.revokeObjectURL(url) }
  return false
}
watch(() => props.active, active => active ? start() : stop(), { immediate: true })
onBeforeUnmount(stop)
</script>
<template>
  <div v-if="active" class="my-12px">
    <video ref="video" muted playsinline class="w-full rounded-12px bg-black" style="max-height: 320px" aria-label="扫描车辆绑定二维码" />
    <Alert v-if="error" type="warning" :message="error" class="my-10px" />
    <div class="flex gap-8px mt-10px">
      <Upload accept="image/*" :show-upload-list="false" :before-upload="image"><Button>从图片识别</Button></Upload>
      <Button @click="start">重试相机</Button><Button @click="stop(); emit('close')">关闭扫描</Button>
    </div>
  </div>
</template>
