<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Alert, Button, Card, List, Switch, Tag, message } from 'ant-design-vue'
import { api } from '@/api/client'
import { useVehiclesStore } from '@/stores/vehicles'
import { useJobsStore } from '@/stores/jobs'
import { fmtDateTime } from '@/utils/format'

const route = useRoute()
const vehicles = useVehiclesStore()
const jobsStore = useJobsStore()

const vehicleId = computed(() => route.params.id as string)
const v = computed(() => vehicles.byId(vehicleId.value))
const pending = ref(false)
const busy = ref(false)
const events = ref<{ at: number; text: string }[]>([])

const actualLabel: Record<string, string> = {
  unknown: '未知', disarmed: '已关闭', armed_standby: '已布防（待机）',
  armed_recording: '已布防（录像中）', restricted: '受限', failed: '失败',
}
const actualColor: Record<string, string> = {
  unknown: 'default', disarmed: 'default', armed_standby: 'green',
  armed_recording: 'green', restricted: 'orange', failed: 'red',
}

async function refresh() {
  vehicles.refreshDetail(vehicleId.value).catch(() => {})
}
onMounted(refresh)
watch(vehicleId, refresh)

async function toggle(enabled: boolean) {
  busy.value = true
  try {
    const { jobId } = await api.sentry.setIntent(vehicleId.value, enabled, v.value?.sentry.revision ?? 1)
    events.value = [{ at: Date.now(), text: enabled ? '布防请求已提交，等待实际状态回读' : '撤防请求已提交' }, ...events.value]
    jobsStore.watch(jobId, {
      onUpdate: () => {},
      onDone: (job) => {
        busy.value = false
        refresh()
        if (job.state === 'failed') message.error(job.reason ?? '布防失败')
        else message.success(enabled ? '布防已生效' : '已撤防')
      },
    })
  } catch (e: any) {
    busy.value = false
    message.error(e?.message ?? '请求失败')
  }
}
</script>

<template>
  <div v-if="v" class="max-w-720px mx-auto flex flex-col gap-16px">
    <h2 class="text-18px font-600 m-0">哨兵模式 · {{ v.name }}</h2>

    <Card class="ar-card" title="布防开关（意图）">
      <div class="flex items-center justify-between">
        <div>
          <div class="text-13px" style="color: var(--ar-text-2)">
            开关表达的是意图；实际布防状态以车端回读为准，不把开关打开当作已布防。
          </div>
        </div>
        <Switch :checked="v.sentry.desired" :loading="busy" :disabled="v.role !== 'owner'" @change="(val: any) => toggle(!!val)" />
      </div>
      <Alert v-if="v.role !== 'owner'" class="mt-10px" type="info" show-icon message="观看者无权更改哨兵。" />
    </Card>

    <Card class="ar-card" title="实际状态">
      <div class="flex items-center gap-10px">
        <Tag :color="actualColor[v.sentry.actual]">{{ actualLabel[v.sentry.actual] ?? v.sentry.actual }}</Tag>
        <span class="text-13px ar-text-2">期望 {{ v.sentry.desired ? '开' : '关' }} · revision {{ v.sentry.revision }}</span>
        <span v-if="pending" class="text-13px" style="color: var(--ar-warn)">· 等待车端回读…</span>
      </div>
      <div class="mt-12px text-13px flex flex-col gap-6px" style="color: var(--ar-text-2)">
        <div>· 电源条件：驻车上电时布防，休眠期间由车端电源适配决定唤醒</div>
        <div>· 存储条件：与行车录像共用配额，空间不足时布防受限</div>
        <div>· 触发后唤醒录像，事件与录像可在录像库中查看</div>
      </div>
    </Card>

    <Card class="ar-card" title="事件（本次会话）">
      <List :data-source="events" size="small">
        <template #renderItem="{ item }">
          <List.Item>
            <span class="text-13px">{{ item.text }}</span>
            <span class="text-12px" style="color: var(--ar-text-2)">{{ fmtDateTime(item.at) }}</span>
          </List.Item>
        </template>
      </List>
      <div v-if="events.length === 0" class="text-13px" style="color: var(--ar-text-2)">暂无事件</div>
    </Card>
  </div>
</template>
