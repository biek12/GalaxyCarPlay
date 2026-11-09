<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Alert, Button, Checkbox, Result, Segmented, Space, Spin, Tag, message } from 'ant-design-vue'
import { LeftOutlined, RightOutlined, PauseOutlined, CaretRightOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { api } from '@/api/client'
import { useVehiclesStore } from '@/stores/vehicles'
import { usePlaybackStore } from '@/stores/playback'
import CameraTile from '@/components/CameraTile.vue'
import DataBar from '@/components/DataBar.vue'
import EventTimeline from '@/components/EventTimeline.vue'
import { fmtDuration, fmtDateTime, fmtBytes } from '@/utils/format'
import { DIR_LABEL, DIRECTIONS, type Clip, type Direction, type TelemetrySample } from '@/api/types'

const route = useRoute()
const router = useRouter()
const vehicles = useVehiclesStore()
const playback = usePlaybackStore()

const vehicleId = computed(() => route.params.id as string)
const clipId = computed(() => route.params.clip as string)
const clip = ref<Clip | null>(null)
const loading = ref(true)
const loadError = ref('')

// —— 播放状态 ——
const playheadMs = ref(0)
const playing = ref(false)
const speed = ref(1)
const layout = ref<'all' | Direction>('all')
const seekNote = ref(false)
const ended = ref(false)
const durationMs = computed(() => clip.value ? clip.value.endAt - clip.value.startAt : 0)
const expanded = ref(true)
const filterKeys = ref(new Set<string>())
const showTimeline = ref(true)

const telemetry = computed(() => playback.cache.get(`${vehicleId.value}/${clipId.value}`) ?? null)

// 事件轨道筛选（key 集合）
const eventKeyOptions = computed(() => {
  const ks = new Set<string>()
  telemetry.value?.events.forEach((e) => ks.add(e.key))
  return [...ks]
})

// —— 数据条样本：按 playhead 取样，缺失/过期不冒充 ——
function sampleAt(ms: number): TelemetrySample | null {
  const t = telemetry.value
  if (!t || !t.samples.length) return null
  // 二分找 <= playhead 的最近样本
  let lo = 0, hi = t.samples.length - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (t.samples[mid].t <= ms) lo = mid
    else hi = mid - 1
  }
  const s = t.samples[lo]
  const age = ms - s.t
  if (age > t.actualIntervalMs * 4) return { ...s, q: 'stale' }
  return s
}
const currentSample = ref<TelemetrySample | null>(null)

// —— 播放循环：统一时轴驱动四路与数据（不拼接、不补帧） ——
let loadGeneration = 0
let raf = 0
let lastTs = 0
function loop(ts: number) {
  raf = requestAnimationFrame(loop)
  if (!playing.value || !clip.value) { lastTs = ts; return }
  const dt = ts - lastTs
  lastTs = ts
  playheadMs.value += dt * speed.value
  if (playheadMs.value >= durationMs.value) {
    playheadMs.value = durationMs.value
    playing.value = false
    ended.value = true
  }
  currentSample.value = sampleAt(playheadMs.value)
}

function togglePlay() {
  if (ended.value) { playheadMs.value = 0; ended.value = false }
  playing.value = !playing.value
}
function doSeek(ms: number, note = true) {
  playheadMs.value = Math.max(0, Math.min(durationMs.value, ms))
  ended.value = false
  currentSample.value = sampleAt(playheadMs.value)
  if (note) {
    seekNote.value = true
    setTimeout(() => { seekNote.value = false }, 300)
  }
}
function jumpEvent(dir: 'prev' | 'next') {
  const evs = telemetry.value?.events ?? []
  const visible = filterKeys.value.size === 0 ? evs : evs.filter((e) => filterKeys.value.has(e.key))
  const cur = playheadMs.value
  if (dir === 'prev') {
    const cands = visible.filter((e) => e.startOffsetMs < cur - 200)
    if (cands.length) doSeek(cands[cands.length - 1].startOffsetMs)
    else doSeek(cur - 10_000)
  } else {
    const cands = visible.filter((e) => e.startOffsetMs > cur + 200)
    if (cands.length) doSeek(cands[0].startOffsetMs)
    else doSeek(cur + 10_000)
  }
}

// —— 跨片段衔接（同会话相邻段） ——
const sessionClips = computed(() => telemetry.value?.sessionClips ?? [])
const prevClip = computed(() => {
  const list = sessionClips.value
  const i = list.findIndex((c) => c.clipId === clipId.value)
  return i > 0 ? list[i - 1] : null
})
const nextClip = computed(() => {
  const list = sessionClips.value
  const i = list.findIndex((c) => c.clipId === clipId.value)
  return i >= 0 && i < list.length - 1 ? list[i + 1] : null
})

onMounted(async () => {
  raf = requestAnimationFrame(loop)
  await load()
})
onBeforeUnmount(() => {loadGeneration++;cancelAnimationFrame(raf)})
watch([vehicleId, clipId], load)

async function load() {
  const ticket = ++loadGeneration
  loading.value = true
  loadError.value = ''
  playing.value = false
  ended.value = false
  playheadMs.value = 0
  try {
    await vehicles.loadList().catch(() => {})
    clip.value = await api.clips.get(vehicleId.value, clipId.value)
    if (!api.mock && clip.value.channels.some(ch => ch.status === 'ok' && !ch.mediaUrl)) {
      const transfer = await api.clips.prepare(vehicleId.value, clipId.value)
      for (let attempt = 0; attempt < 150; attempt++) {
        if (ticket !== loadGeneration) return
        const job = await api.jobs.get(transfer.jobId)
        if (['failed', 'expired', 'cancelled', 'partial_success'].includes(job.state)) throw new Error(job.reason || '车端原片准备失败，请重试')
        if (job.state === 'success') break
        if (attempt === 149) throw new Error('原片传输超时，请稍后重试')
        await new Promise(resolve => setTimeout(resolve, 2000))
      }
      if (ticket !== loadGeneration) return
      clip.value = await api.clips.get(vehicleId.value, clipId.value)
    }
    await playback.loadTelemetry(vehicleId.value, clipId.value)
    currentSample.value = sampleAt(0)
  } catch (e: any) {
    loadError.value = e?.message ?? '片段加载失败'
  } finally {
    loading.value = false
  }
}
function openClip(cid: string) {
  router.push(`/vehicles/${vehicleId.value}/clips/${cid}`)
}

// 各路画面映射：tCh = playhead − 起始偏差；出界即缺帧
function stillT(d: Direction): number | undefined {
  if (!clip.value) return undefined
  const ch = clip.value.channels.find((c) => c.direction === d)
  if (!ch) return undefined
  const t = (playheadMs.value - ch.mediaStartOffsetMs) / 1000
  if (t < 0 || t > (ch.durationMs || durationMs.value) / 1000) return -1 // 出界 → 占位
  return t
}
function chStatus(d: Direction): 'streaming' | 'missing' {
  const ch = clip.value?.channels.find((c) => c.direction === d)
  if (!ch || ch.status === 'missing') return 'missing'
  if (clip.value?.availability === 'missing') return 'missing'
  const t = stillT(d)
  return t == null || t < 0 ? 'missing' : 'streaming'
}
const channels = computed(() => (layout.value === 'all' ? DIRECTIONS : [layout.value] as Direction[]))

const visibleEventsCount = computed(() => {
  const evs = telemetry.value?.events ?? []
  return filterKeys.value.size === 0 ? evs.length : evs.filter((e) => filterKeys.value.has(e.key)).length
})
</script>

<template>
  <div class="max-w-1080px mx-auto flex flex-col gap-12px">
    <!-- 顶部：返回 + 标题 + 片段信息 -->
    <div class="flex items-center gap-10px flex-wrap">
      <Button size="small" @click="router.back()"><LeftOutlined /> 返回</Button>
      <h2 class="text-16px font-600 m-0 tabular-nums">
        {{ clip ? fmtDateTime(clip.startAt) : '…' }} · {{ fmtDuration(durationMs / 1000) }}
      </h2>
      <Tag v-if="clip" color="blue">{{ clip.source === 'cloud' ? '云端' : '车端本地' }}</Tag>
      <Tag v-if="clip?.availability === 'partial'" color="orange">部分有效</Tag>
      <Tag v-if="clip?.availability === 'recoverable'" color="orange">可恢复</Tag>
      <Tag v-if="clip?.locked" color="gold">已锁定</Tag>
      <span v-if="clip" class="text-12px ar-text-2">{{ fmtBytes(clip.bytes) }} · 采样 {{ clip.telemetry.actualIntervalMs }}ms</span>
      <div class="flex-1" />
      <Segmented v-model:value="layout" :options="[
        { label: '全部', value: 'all' }, ...DIRECTIONS.map((d) => ({ label: DIR_LABEL[d], value: d }))]" />
    </div>

    <Alert v-if="loadError" type="error" show-icon :message="loadError" />

    <template v-if="!loading && clip">
      <!-- 已覆盖/未上传：不放可点击但永远失败的播放入口 -->
      <Result v-if="clip.availability === 'overwritten'"
        status="warning" title="该片段已覆盖 / 未上传"
        sub-title="本地原片已被循环覆盖，云端无副本，无法播放。"
        style="padding: 40px 0">
        <template #extra>
          <Button @click="router.back()">返回录像库</Button>
        </template>
      </Result>

      <template v-else-if="clip.availability === 'missing'">
        <Result status="warning" title="片段文件缺失"
          sub-title="索引存在但四路视频与侧录文件缺失，无法播放；不代表车辆当时没有录像。" style="padding: 40px 0">
          <template #extra><Button @click="router.back()">返回录像库</Button></template>
        </Result>
      </template>

      <template v-else>
        <!-- 播放器 -->
        <div class="grid gap-12px" :class="layout === 'all' ? 'grid-cols-1 md:grid-cols-2' : 'grid-cols-1'">
          <CameraTile v-for="d in channels" :key="d" :direction="d" :status="chStatus(d)" :still-t="stillT(d)" :media-url="clip?.channels.find(c => c.direction === d)?.mediaUrl" :playing="playing" :speed="speed" />
        </div>
        <div v-if="clip.channels.some((c) => c.status !== 'ok')" class="text-13px ar-text-2">
          缺路：{{ clip.channels.filter((c) => c.status !== 'ok').map((c) => DIR_LABEL[c.direction]).join('、') }}
          —— 对应格以占位呈现，不借用其他方向画面。
        </div>

        <!-- 播放控制 -->
        <div class="ar-panel px-12px py-10px flex flex-col gap-8px">
          <input type="range" class="w-full" :min="0" :max="durationMs" :value="playheadMs" step="100"
            @input="doSeek(Number(($event.target as HTMLInputElement).value))" />
          <div class="flex items-center gap-10px flex-wrap">
            <Space>
              <Button size="small" @click="jumpEvent('prev')">◀ 上一事件</Button>
              <Button size="small" type="primary" @click="togglePlay">
                <PauseOutlined v-if="playing" /><CaretRightOutlined v-else />
                {{ playing ? '暂停' : ended ? '重播' : '播放' }}
              </Button>
              <Button size="small" @click="jumpEvent('next')">下一事件 ▶</Button>
            </Space>
            <span class="tabular-nums text-13px">
              {{ fmtDuration(playheadMs / 1000) }} / {{ fmtDuration(durationMs / 1000) }}
            </span>
            <Tag v-if="seekNote" color="green">正在定位…</Tag>
            <Tag v-if="ended" color="green">播放完毕</Tag>
            <div class="flex-1" />
            <Segmented v-model:value="speed" :options="[
              { label: '0.5×', value: 0.5 }, { label: '1×', value: 1 }, { label: '2×', value: 2 }]" />
          </div>
        </div>

        <!-- 数据区（历史样本，来自 sidecar） -->
        <DataBar :sample="currentSample" :interval-ms="clip.telemetry.actualIntervalMs" :expanded="expanded" />
        <div class="text-right">
          <a class="text-13px" style="color: var(--ar-accent)" @click="expanded = !expanded">{{ expanded ? '收起' : '展开全量' }}</a>
        </div>

        <!-- 事件时间轴 -->
        <div class="ar-panel px-12px py-10px">
          <div class="flex items-center gap-10px flex-wrap mb-8px">
            <span class="text-14px font-600">事件时间轴</span>
            <Tag>{{ visibleEventsCount }} 个事件</Tag>
            <Checkbox :checked="showTimeline" @change="(e: any) => showTimeline = e.target.checked">展开</Checkbox>
            <div class="flex-1" />
            <div class="flex gap-6px flex-wrap">
              <Checkbox v-for="k in eventKeyOptions" :key="k"
                :checked="filterKeys.size === 0 || filterKeys.has(k)"
                @change="(e: any) => {
                  const s = new Set(filterKeys)
                  if (e.target.checked) s.add(k); else s.delete(k)
                  filterKeys = s.size === eventKeyOptions.length ? new Set() : s
                }">{{ k }}</Checkbox>
            </div>
          </div>
          <template v-if="showTimeline && telemetry">
            <EventTimeline :events="telemetry.events" :duration-ms="durationMs" :playhead-ms="playheadMs"
              :filter-keys="filterKeys" @seek="(ms) => doSeek(ms)" />
          </template>
          <div v-if="telemetry && telemetry.events.length === 0" class="text-13px py-8px" style="color: var(--ar-text-2)">
            本段无事件
          </div>
        </div>

        <!-- 跨片段衔接 -->
        <div v-if="prevClip || nextClip" class="flex items-center gap-10px text-13px">
          <span class="ar-text-2">同会话跨段：</span>
          <Button v-if="prevClip" size="small" @click="openClip(prevClip.clipId)">上一段（{{ fmtDateTime(prevClip.startAt) }}）</Button>
          <Button v-if="nextClip" size="small" @click="openClip(nextClip.clipId)">下一段（{{ fmtDateTime(nextClip.startAt) }}）</Button>
          <span class="ar-text-2 text-12px">每段使用自己的旁路数据，不拼接时间轴。</span>
        </div>
      </template>
    </template>

    <div v-else-if="loading" class="py-24 text-center"><Spin /></div>
  </div>
</template>
