<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Alert, Button, Card, Descriptions, Progress, Tag, message } from 'ant-design-vue'
import { UserOutlined } from '@ant-design/icons-vue'
import { useVehiclesStore } from '@/stores/vehicles'
import { useJobsStore } from '@/stores/jobs'
import { api } from '@/api/client'
import { PRESENCE_COLOR, PRESENCE_LABEL } from '@/utils/presence'
import { fmtBytes, fmtDateTime, fmtDuration, fmtRelTime } from '@/utils/format'
import { DIR_LABEL, DIRECTIONS } from '@/api/types'

const route = useRoute()
const router = useRouter()
const vehicles = useVehiclesStore()
const jobs = useJobsStore()

const vehicleId = computed(() => route.params.id as string)
const v = computed(() => vehicles.byId(vehicleId.value))
const wakeJob = ref<{ state: string; reason?: string } | null>(null)
let timer = 0

async function refresh() {
  try { await vehicles.refreshDetail(vehicleId.value) } catch { /* 离线等情况忽略 */ }
}
onMounted(refresh)
watch(vehicleId, refresh)
timer = window.setInterval(refresh, 10_000)
onBeforeUnmount(() => clearInterval(timer))

async function wake() {
  try {
    const { jobId } = await api.vehicles.wake(vehicleId.value)
    jobs.watch(jobId, {
      onUpdate: (job) => { wakeJob.value = { state: job.state, reason: job.reason } },
      onDone: (job) => {
        wakeJob.value = { state: job.state, reason: job.reason }
        if (job.state === 'ready') { message.success('车辆已就绪'); refresh() }
        else if (job.state === 'failed') message.error(job.reason ?? '唤醒失败')
      },
    })
  } catch (e: any) {
    message.error(e?.message ?? '唤醒请求失败')
  }
}
const storagePct = computed(() => {
  if (!v.value) return 0
  return Math.min(100, Math.round((v.value.storage.usedBytes / v.value.storage.quotaBytes) * 100))
})

const sentryActualLabel: Record<string, string> = {
  unknown: '未知', disarmed: '已关闭', armed_standby: '已布防（待机）', armed_recording: '已布防（录像中）', restricted: '受限', failed: '失败',
}
</script>

<template>
  <div v-if="v" class="max-w-880px mx-auto flex flex-col gap-16px">
    <div class="flex items-center justify-between flex-wrap gap-8px">
      <div class="flex items-center gap-10px">
        <Button size="small" @click="router.push('/vehicles')">← 我的车辆</Button>
        <h2 class="text-18px font-600 m-0">{{ v.name }}</h2>
        <Tag :color="v.role === 'owner' ? 'green' : 'blue'">{{ v.role === 'owner' ? '车主' : '观看者' }}</Tag>
      </div>
    </div>

    <Card class="ar-card" title="在线与唤醒">
      <div class="flex items-center gap-10px mb-10px">
        <i class="presence-dot" :style="{ background: PRESENCE_COLOR[v.presence.state] }" />
        <span class="text-15px font-600">{{ PRESENCE_LABEL[v.presence.state] }}</span>
        <span class="text-13px ar-text-2">最近在线 {{ fmtDateTime(v.presence.lastSeenAt) }}（{{ fmtRelTime(v.presence.lastSeenAt) }}）</span>
      </div>

      <template v-if="v.presence.state !== 'online'">
        <Alert v-if="v.capabilities.remoteWake.supported" type="info" show-icon class="mb-10px"
          message="车辆当前不在线，可发起远程唤醒后观看实时画面。" />
        <Alert v-else type="warning" show-icon class="mb-10px"
          :message="`当前车辆不支持远程唤醒：${v.capabilities.remoteWake.reason ?? '未说明原因'}`" />
        <div v-if="wakeJob" class="text-13px mb-10px">
          唤醒作业：{{ wakeJob.state === 'ready' ? '已完成' : wakeJob.state === 'failed' ? '失败' : '进行中（' + wakeJob.state + '）' }}
          <span v-if="wakeJob.reason" class="ar-danger"> · {{ wakeJob.reason }}</span>
        </div>
        <Button type="primary" :disabled="!v.capabilities.remoteWake.supported"
          :loading="!!wakeJob && !['ready', 'failed'].includes(wakeJob.state)" @click="wake">
          唤醒并观看
        </Button>
      </template>
      <div v-else class="text-13px" style="color: var(--ar-text-2)">车辆在线，可直接进入实时画面。</div>
    </Card>

    <Card class="ar-card" title="录制与存储">
      <Descriptions :column="2" size="small">
        <Descriptions.Item label="录制状态">
          <span v-if="v.recording.state === 'recording'" class="flex items-center gap-6px">
            <i class="rec-dot" /> 录制中（已录 {{ fmtDuration((Date.now() - (v.recording.startedAt ?? Date.now())) / 1000) }}）
          </span>
          <span v-else-if="v.recording.state === 'idle'">未录制</span>
          <span v-else class="ar-text-2">未知</span>
        </Descriptions.Item>
        <Descriptions.Item label="存储介质">{{ v.storage.volume }}</Descriptions.Item>
        <Descriptions.Item label="剩余空间">{{ fmtBytes(v.storage.freeBytes) }}</Descriptions.Item>
        <Descriptions.Item label="录像配额">{{ fmtBytes(v.storage.quotaBytes) }}</Descriptions.Item>
      </Descriptions>
      <Progress :percent="storagePct" :status="storagePct > 90 ? 'exception' : 'normal'" class="mt-6px" />
    </Card>

    <Card class="ar-card" title="能力">
      <Descriptions :column="1" size="small">
        <Descriptions.Item label="远程唤醒">
          <Tag :color="v.capabilities.remoteWake.supported ? 'green' : 'red'">
            {{ v.capabilities.remoteWake.supported ? '支持' : '不支持' }}
          </Tag>
          <span v-if="!v.capabilities.remoteWake.supported" class="ar-text-2 text-13px">{{ v.capabilities.remoteWake.reason }}</span>
        </Descriptions.Item>
        <Descriptions.Item label="平面画面标定">
          <span v-if="DIRECTIONS.every((d) => !v?.capabilities.flatView[d])" class="ar-text-2 text-13px">
            暂无可用标定，只能观看原始鱼眼画面
          </span>
          <span v-else>可用方向：{{ DIRECTIONS.filter((d) => v?.capabilities.flatView[d]).map((d) => DIR_LABEL[d]).join(' / ') }}</span>
        </Descriptions.Item>
        <Descriptions.Item label="哨兵模式">
          <Tag>{{ sentryActualLabel[v.sentry.actual] ?? v.sentry.actual }}</Tag>
          <span class="ar-text-2 text-13px">期望 {{ v.sentry.desired ? '开' : '关' }} · revision {{ v.sentry.revision }}</span>
        </Descriptions.Item>
      </Descriptions>
    </Card>

    <Card class="ar-card" title="更多">
      <button class="flex items-center gap-10px w-full text-left px-10px py-10px rounded-10px text-14px"
        :style="{ background: 'var(--ar-bg)' }"
        @click="router.push(`/vehicles/${v.vehicleId}/access`)">
        <UserOutlined /> 归属与授权
        <span class="ml-auto text-12px" style="color: var(--ar-text-2)">成员、解绑 ›</span>
      </button>
    </Card>
  </div>
</template>
