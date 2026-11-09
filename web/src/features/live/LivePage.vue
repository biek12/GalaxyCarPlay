<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Alert, Button, Card, Segmented, Tag, Tooltip, message } from 'ant-design-vue'
import { api } from '@/api/client'
import { useVehiclesStore } from '@/stores/vehicles'
import { useJobsStore } from '@/stores/jobs'
import { loadScenarios } from '@/api/mock/server'
import CameraTile from '@/components/CameraTile.vue'
import { WebRtcReceiver } from './WebRtcReceiver'
const streams = ref<Partial<Record<Direction, MediaStream>>>({})
let receiver: WebRtcReceiver | null = null
import DataBar from '@/components/DataBar.vue'
import { PRESENCE_COLOR, PRESENCE_LABEL } from '@/utils/presence'
import { fmtDuration } from '@/utils/format'
import { DIR_LABEL, DIRECTIONS, type Direction, type LiveSession } from '@/api/types'

const route = useRoute()
const vehicles = useVehiclesStore()
const jobs = useJobsStore()

const vehicleId = computed(() => route.params.id as string)
const v = computed(() => vehicles.byId(vehicleId.value))
const wakeJob = ref<{ state: string; reason?: string } | null>(null)
const session = ref<(LiveSession & { startedAt?: number }) | null>(null)
const channelStatus = ref<Record<Direction, 'streaming' | 'preparing' | 'missing' | 'occupied' | 'stalled'>>({
  front: 'preparing', rear: 'preparing', left: 'preparing', right: 'preparing',
})
const layout = ref<'all' | Direction>('all')
const flat = ref<Record<Direction, boolean>>({ front: false, rear: false, left: false, right: false })
const expanded = ref(true)
const liveSample = ref<Awaited<ReturnType<typeof api.liveSample>> | null>(null)
const nowTick = ref(Date.now())
let sampleTimer = 0
let tickTimer = 0
let firstFrameTimer = 0

// —— 会话生命周期：进入页面建立观看会话，离开即释放 ——
async function startSession() {
  if (!v.value || v.value.presence.state !== 'online') return
  try {
    const s = await api.vehicles.startLive(vehicleId.value)
    session.value = { ...s, startedAt: Date.now() }
    for (const c of s.channels) {
      channelStatus.value[c.direction] = c.status === 'ok' ? 'preparing' : c.status === 'occupied' ? 'occupied' : 'missing'
    }
    if (!api.mock) {
      receiver = new WebRtcReceiver(vehicleId.value, s, (direction, stream) => {
        streams.value = { ...streams.value, [direction]: stream }
        channelStatus.value[direction] = 'preparing'
      }, reason => { DIRECTIONS.forEach(d => channelStatus.value[d] = 'stalled'); message.error(reason) })
      await receiver.start()
    }
    clearTimeout(firstFrameTimer)
    if (api.mock) firstFrameTimer = window.setTimeout(() => {
      for (const d of DIRECTIONS) if (channelStatus.value[d] === 'preparing') channelStatus.value[d] = 'streaming'
    }, 1400)
  } catch (e: any) {
    message.error(e?.message ?? '建立观看会话失败')
  }
}
async function stopSession() {
  receiver?.close(); receiver = null; streams.value = {}
  if (!session.value) return
  try { await api.vehicles.stopLive(vehicleId.value, session.value.sessionId) } catch { /* 忽略 */ }
  session.value = null
}

onMounted(async () => {
  window.addEventListener('pagehide', stopSession)
  await vehicles.refreshDetail(vehicleId.value).catch(() => {})
  if (v.value?.presence.state === 'online') await startSession()
  sampleTimer = window.setInterval(async () => {
    if (session.value) liveSample.value = await api.liveSample(vehicleId.value, Date.now() % 300_000).catch(() => null)
  }, 500)
  tickTimer = window.setInterval(() => {
    nowTick.value = Date.now()
    // 场景：卡顿 → 遮蔽旧画面（帧龄超限），恢复后继续
    if (!api.mock) return
    const s = loadScenarios()
    for (const d of DIRECTIONS) {
      if (channelStatus.value[d] === 'streaming' && s.streamStall) channelStatus.value[d] = 'stalled'
      else if (channelStatus.value[d] === 'stalled' && !s.streamStall) channelStatus.value[d] = 'streaming'
    }
  }, 1000)
})
onBeforeUnmount(() => {
  window.removeEventListener('pagehide', stopSession)
  clearTimeout(firstFrameTimer)
  clearInterval(sampleTimer)
  clearInterval(tickTimer)
  stopSession()
})
watch(vehicleId, async () => {
  await stopSession()
  channelStatus.value = { front: 'preparing', rear: 'preparing', left: 'preparing', right: 'preparing' }
  await vehicles.refreshDetail(vehicleId.value).catch(() => {})
  if (v.value?.presence.state === 'online') await startSession()
})

// —— 唤醒 ——
async function wake() {
  try {
    const { jobId } = await api.vehicles.wake(vehicleId.value)
    jobs.watch(jobId, {
      onUpdate: (job) => { wakeJob.value = { state: job.state, reason: job.reason } },
      onDone: (job) => {
        wakeJob.value = { state: job.state, reason: job.reason }
        if (job.state === 'ready') { vehicles.refreshDetail(vehicleId.value); startSession() }
      },
    })
  } catch (e: any) {
    message.error(e?.message ?? '唤醒请求失败')
  }
}

// —— 录制徽标 ——
const recElapsed = computed(() => {
  if (v.value?.recording.state !== 'recording' || !v.value.recording.startedAt) return null
  return (nowTick.value - v.value.recording.startedAt) / 1000
})
const healthColor = computed(() => {
  if (!v.value) return '#9aa3ad'
  if (v.value.recording.state !== 'recording') return '#9aa3ad'
  const s = channelStatus.value
  const missing = DIRECTIONS.filter((d) => ['missing', 'occupied', 'stalled'].includes(s[d])).length
  return missing === 0 ? '#00b96b' : missing < 4 ? '#e6a700' : '#e5484d'
})

async function toggleFlat(d: Direction) {
  if (!v.value?.capabilities.flatView[d]) return
  const next = { ...flat.value, [d]: !flat.value[d] }
  if (!api.mock && session.value) { try { await api.vehicles.liveView(vehicleId.value, session.value.sessionId, next) } catch { message.error('切换画面失败'); return } }
  flat.value = next
}
const sessionRemain = computed(() => {
  if (!session.value) return 0
  return Math.max(0, Math.floor((session.value.expiresAt - nowTick.value) / 1000))
})
</script>

<template>
  <div v-if="v" class="max-w-1080px mx-auto flex flex-col gap-12px">
    <!-- 标题徽标行：与 App P1 标题区一致 -->
    <div class="flex items-center gap-12px flex-wrap">
      <h2 class="text-18px font-600 m-0">实时画面 · {{ v.name }}</h2>
      <Tag class="flex items-center gap-6px">
        <i v-if="v.recording.state === 'recording'" class="rec-dot" />
        <span v-if="recElapsed != null">REC {{ fmtDuration(recElapsed) }}</span>
        <span v-else>未录制</span>
        <i class="inline-block w-8px h-8px rounded-full ml-4px" :style="{ background: healthColor }" title="录像健康点" />
      </Tag>
      <Tag>存储 {{ v.storage.volume }} · 剩余 {{ (v.storage.freeBytes / 1e9).toFixed(1) }} GB</Tag>
      <Tag v-if="session" color="green">观看中 · {{ Math.floor(sessionRemain / 60) }} 分后断开</Tag>
    </div>

    <!-- 状态条 -->
    <div class="flex items-center justify-between gap-8px flex-wrap">
      <div class="flex items-center gap-8px text-13px">
        <i class="presence-dot" :style="{ background: PRESENCE_COLOR[v.presence.state] }" />
        {{ PRESENCE_LABEL[v.presence.state] }}
        <span v-if="v.presence.state !== 'online'" class="ar-text-2">· 需要唤醒后才能观看实时画面</span>
      </div>
      <Segmented v-model:value="layout" :options="[{ label: '四路', value: 'all' }, ...DIRECTIONS.map((d) => ({ label: DIR_LABEL[d], value: d }))]" />
    </div>

    <!-- 唤醒区 -->
    <Card v-if="v.presence.state !== 'online'" class="ar-card">
      <template v-if="v.capabilities.remoteWake.supported">
        <div class="mb-10px text-13px">
          车辆 {{ PRESENCE_LABEL[v.presence.state] }}。HTTP 接受请求不等于车机已醒，只有收到真实心跳与首帧才算就绪。
        </div>
        <div v-if="wakeJob" class="text-13px mb-10px">
          唤醒作业：{{ wakeJob.state === 'ready' ? '完成' : wakeJob.state === 'failed' ? '失败' : wakeJob.state }}
          <span v-if="wakeJob.reason" class="ar-danger"> · {{ wakeJob.reason }}</span>
        </div>
        <Button type="primary" :loading="!!wakeJob && !['ready', 'failed'].includes(wakeJob.state)" @click="wake">
          唤醒并观看
        </Button>
      </template>
      <Alert type="warning" show-icon :message="`不支持远程唤醒：${v.capabilities.remoteWake.reason ?? '原因未说明'}`" />
    </Card>

    <!-- 四宫格 / 单路 -->
    <div v-else class="grid gap-12px"
      :class="layout === 'all' ? 'grid-cols-1 md:grid-cols-2' : 'grid-cols-1'">
      <template v-for="d in (layout === 'all' ? DIRECTIONS : [layout] as Direction[])" :key="d">
        <div>
          <CameraTile :direction="d" :status="channelStatus[d]" :flat="flat[d]" :stream="streams[d]" @first-frame="channelStatus[d] = 'streaming'" @stalled="channelStatus[d] = 'stalled'" />
          <div class="flex items-center justify-between mt-4px text-13px">
            <span class="ar-text-2">{{ DIR_LABEL[d] }} · {{ api.mock ? '演示画面' : '1280×800 · H.264 实时视频' }}</span>
            <Tooltip :title="v.capabilities.flatView[d] ? '切换鱼眼/平面' : '标定不可用：未核验本车标定参数'">
              <Button size="small" :disabled="!v.capabilities.flatView[d]" @click="toggleFlat(d)">
                {{ flat[d] ? '广角平面' : '鱼眼' }}
              </Button>
            </Tooltip>
          </div>
        </div>
      </template>
    </div>

    <!-- 数据条：折叠一行 4 项，展开全量（与 App P1 一致） -->
    <DataBar :sample="liveSample" :interval-ms="500" :expanded="expanded" />
    <div class="text-right">
      <a class="text-13px" style="color: var(--ar-accent)" @click="expanded = !expanded">{{ expanded ? '收起' : '展开全量' }}</a>
    </div>

    <div class="text-12px ar-text-2">
      离开本页将立即释放仅为观看建立的资源，不影响车端本地行车录制。
    </div>
  </div>
</template>
