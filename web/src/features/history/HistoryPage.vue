<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Alert, Button, Card, Checkbox, DatePicker, Drawer, Empty, Modal, Radio, Segmented, Select,
  Space, Spin, Tag, message,
} from 'ant-design-vue'
import { ClearOutlined, DeleteOutlined, FilterOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import dayjs, { type Dayjs } from 'dayjs'
import { api } from '@/api/client'
import { useVehiclesStore } from '@/stores/vehicles'
import { useJobsStore } from '@/stores/jobs'
import { clipThumb } from '@/api/mock/video'
import { fmtBytes, fmtDuration, fmtTime } from '@/utils/format'
import { DIR_LABEL, type Clip, type Job } from '@/api/types'
import ReauthModal from '@/features/security/ReauthModal.vue'

const route = useRoute()
const router = useRouter()
const vehicles = useVehiclesStore()
const jobsStore = useJobsStore()

const vehicleId = computed(() => route.params.id as string)
const v = computed(() => vehicles.byId(vehicleId.value))

// —— 筛选（默认最近 7 天，跨午夜直接支持） ——
const range = ref<[Dayjs, Dayjs]>([dayjs().subtract(7, 'day').startOf('day'), dayjs()])
const statusFilter = ref<'all' | 'important' | 'abnormal' | 'unwatched'>('all')
const sourceFilter = ref<'all' | 'local' | 'cloud'>('all')
const filterDrawer = ref(false)

// —— 列表 ——
const items = ref<Clip[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const indexReady = ref(true)
const selected = ref(new Set<string>())
const selectMode = ref(false)
const firstLoad = ref(true)

async function load(reset = true) {
  if (!vehicleId.value) return
  loading.value = true
  try {
    const r = await api.clips.list(vehicleId.value, {
      from: range.value[0].valueOf(),
      until: range.value[1].valueOf(),
      filter: statusFilter.value,
      source: sourceFilter.value,
      cursor: reset ? undefined : cursor.value ?? undefined,
    })
    indexReady.value = r.indexReady
    items.value = reset ? r.items : [...items.value, ...r.items]
    cursor.value = r.nextCursor
  } catch (e: any) {
    message.error(e?.message ?? '加载录像库失败')
  } finally {
    loading.value = false
    firstLoad.value = false
  }
}

function clearFilters() {
  statusFilter.value = 'all'
  sourceFilter.value = 'all'
  range.value = [dayjs().subtract(7, 'day').startOf('day'), dayjs()]
  load(true)
}
watch([statusFilter, sourceFilter], () => load(true))
watch(vehicleId, () => { items.value = []; cursor.value = null; selected.value.clear(); load(true) })
onMounted(async () => {
  await vehicles.loadList().catch(() => {})
  load(true)
})

// —— 按日分组 ——
const groups = computed(() => {
  const map = new Map<string, Clip[]>()
  for (const c of items.value) {
    const d = dayjs(c.startAt).format('YYYY-MM-DD')
    if (!map.has(d)) map.set(d, [])
    map.get(d)!.push(c)
  }
  return [...map.entries()].sort((a, b) => (a[0] < b[0] ? 1 : -1))
})

// —— 角标（优先级与 App 一致，最多 2 个 + +N） ——
function badges(c: Clip): { text: string; color: string }[] {
  const list: { text: string; color: string }[] = []
  if (c.availability === 'missing') list.push({ text: '文件缺失', color: 'red' })
  if (c.availability === 'partial') list.push({ text: '部分有效', color: 'orange' })
  if (c.availability === 'recoverable') list.push({ text: '可恢复', color: 'orange' })
  const missCh = c.channels.filter((ch) => ch.status !== 'ok')
  if (missCh.length) list.push({ text: `缺路 ${missCh.map((ch) => DIR_LABEL[ch.direction]).join('')}`, color: 'orange' })
  if (c.locked) list.push({ text: '已锁定', color: 'gold' })
  if (c.eventCount > 0) list.push({ text: `含事件 ${c.eventCount}`, color: 'green' })
  if (!c.watched && c.availability === 'ok') list.push({ text: '未播放', color: 'default' })
  return list
}

function thumb(c: Clip): string {
  const dirs = c.channels.map((ch) => ch.direction) as any[]
  const missing = c.channels.map((ch) => ch.status !== 'ok')
  // 缺路格显示深色占位（在 thumbnail 内部已有占位逻辑，由组件渲染）
  return clipThumb(c.clipId, dirs, [0.3, 0.9, 1.7, 2.4])
}

// —— 多选与删除 ——
const deleteOpen = ref(false)
const scope = ref<'cloud' | 'local'>('cloud')
const reauthOpen = ref(false)
const job = ref<Job | null>(null)
const deleting = ref(false)

function toggleSelect(id: string) {
  if (selected.value.has(id)) selected.value.delete(id)
  else selected.value.add(id)
}
function exitSelect() {
  selectMode.value = false
  selected.value.clear()
}
function openDelete() {
  if (!selected.value.size) return
  job.value = null
  deleteOpen.value = true
}
const lockedSelected = computed(() => items.value.filter((c) => selected.value.has(c.clipId) && c.locked).length)

async function submitDelete(ticket?: string) {
  deleting.value = true
  try {
    let actionTicket = ticket
    if (!actionTicket) {
      reauthOpen.value = true
      deleting.value = false
      return
    }
    const { jobId } = await api.clips.delete(vehicleId.value, [...selected.value], scope.value, actionTicket)
    deleteOpen.value = false
    job.value = { jobId, state: 'queued', type: 'clip-delete', createdAt: Date.now(), updatedAt: Date.now() }
    jobsStore.watch(jobId, {
      onUpdate: (j) => { job.value = j },
      onDone: (j) => {
        job.value = j
        deleting.value = false
        exitSelect()
        load(true)
      },
    })
  } catch (e: any) {
    message.error(e?.message ?? '提交删除失败')
  } finally {
    deleting.value = false
  }
}

const itemStateColor: Record<string, string> = { success: 'green', skipped: 'orange', failed: 'red' }
const itemStateText: Record<string, string> = { success: '已删除', skipped: '已跳过', failed: '失败' }

function clipStartText(clipId: string): string {
  const c = items.value.find((x) => x.clipId === clipId)
  return c ? `${dayjs(c.startAt).format('MM-DD HH:mm:ss')} · ${c.clipId.slice(-6)}` : clipId
}

onBeforeUnmount(() => jobsStore.watching.forEach((w, id) => jobsStore.stop(id)))
</script>

<template>
  <div v-if="v" class="max-w-1080px mx-auto flex flex-col gap-12px">
    <!-- 标题与筛选 -->
    <div class="flex items-center justify-between gap-8px flex-wrap">
      <div class="flex items-center gap-10px">
        <h2 class="text-18px font-600 m-0">录像库 · {{ v.name }}</h2>
        <Tag>{{ indexReady ? `片段 ${items.length}` : '片段 —' }}</Tag>
        <Tag>{{ v.role === 'owner' ? '车主' : '观看者（只读）' }}</Tag>
      </div>
      <Space wrap>
        <Segmented v-model:value="statusFilter" :options="[
          { label: '全部', value: 'all' }, { label: '重要', value: 'important' },
          { label: '异常', value: 'abnormal' }, { label: '未看', value: 'unwatched' }]" />
        <DatePicker.RangePicker v-model:value="range" show-time format="YYYY-MM-DD HH:mm"
          :placeholders="['开始时间', '结束时间']" @change="load(true)" />
        <Button @click="filterDrawer = true"><FilterOutlined /> 更多</Button>
        <Button @click="clearFilters"><ClearOutlined /> 清除</Button>
        <Button v-if="!selectMode" @click="selectMode = true">多选</Button>
        <template v-else>
          <span class="text-13px">已选 {{ selected.size }}</span>
          <Button danger :disabled="!selected.size" @click="openDelete"><DeleteOutlined /> 删除</Button>
          <Button @click="exitSelect">取消</Button>
        </template>
      </Space>
    </div>

    <Alert v-if="v.role === 'viewer'" type="info" show-icon
      message="你被授权观看该车辆，不能删除录像或更改哨兵。" />

    <!-- 空态 -->
    <div v-if="!loading && items.length === 0" class="ar-panel p-24px">
      <Empty :description="firstLoad ? '加载中' : statusFilter !== 'all' || sourceFilter !== 'all'
        ? '没有匹配当前筛选的录像' : indexReady ? '该时间范围内还没有录像' : '正在重建索引'" />
      <div class="text-center">
        <Button type="primary" v-if="indexReady" @click="router.push(`/vehicles/${vehicleId}/live`)">去实时画面</Button>
      </div>
    </div>

    <Spin :spinning="loading">
      <div v-for="[day, clips] in groups" :key="day" class="mb-16px">
        <div class="text-13px mb-8px" style="color: var(--ar-text-2)">
          {{ day }} · {{ clips.length }} 段
        </div>
        <div class="grid gap-12px" style="grid-template-columns: repeat(auto-fill, minmax(240px, 1fr))">
          <Card v-for="c in clips" :key="c.clipId" size="small" class="ar-card overflow-hidden cursor-pointer"
            :class="{ 'opacity-60': selectMode && !selected.has(c.clipId) }"
            @click="selectMode ? toggleSelect(c.clipId) : router.push(`/vehicles/${vehicleId}/clips/${c.clipId}`)">
            <template #cover>
              <div style="aspect-ratio: 16/9; position: relative; background: #0a0d12">
                <img v-if="c.availability !== 'missing'" :src="thumb(c)" style="width: 100%; height: 100%; object-fit: cover; display: block" />
                <div v-else class="flex items-center justify-center h-full text-13px" style="color:#5f6a76">文件缺失 · 无缩略图</div>
                <div style="position: absolute; right: 6px; bottom: 6px" class="text-11px px-6px rounded-4px"
                  :style="{ background: 'rgba(0,0,0,.6)', color: '#fff' }">
                  {{ fmtDuration((c.endAt - c.startAt) / 1000) }}
                </div>
                <div v-if="c.source === 'cloud'" style="position: absolute; left: 6px; bottom: 6px" class="text-11px px-6px rounded-4px"
                  :style="{ background: 'rgba(47,125,209,.85)', color: '#fff' }">云</div>
              </div>
            </template>
            <div class="flex flex-col gap-4px">
              <div class="text-13px tabular-nums">{{ dayjs(c.startAt).format('YYYY-MM-DD HH:mm:ss') }}</div>
              <div class="flex gap-4px flex-wrap">
                <template v-for="(b, i) in badges(c).slice(0, 2)" :key="b.text">
                  <Tag :color="b.color" style="font-size: 11px; padding: 0 6px; line-height: 18px; margin: 0">{{ b.text }}</Tag>
                </template>
                <Tag v-if="badges(c).length > 2" style="font-size: 11px; padding: 0 6px; line-height: 18px; margin: 0">
                  +{{ badges(c).length - 2 }}
                </Tag>
                <Tag v-if="c.availability === 'overwritten'" color="red" style="font-size: 11px; padding: 0 6px; line-height: 18px; margin: 0">已覆盖/未上传</Tag>
              </div>
              <div class="flex items-center justify-between text-12px" style="color: var(--ar-text-2)">
                <span>{{ fmtBytes(c.bytes) }} · {{ c.telemetry.actualIntervalMs }}ms 采样</span>
                <Checkbox v-if="selectMode" :checked="selected.has(c.clipId)" @click.stop @change="toggleSelect(c.clipId)" />
              </div>
            </div>
          </Card>
        </div>
      </div>

      <div v-if="cursor" class="text-center py-8px">
        <Button :loading="loading" @click="load(false)">加载更多</Button>
      </div>
    </Spin>

    <!-- 更多筛选抽屉 -->
    <Drawer v-model:open="filterDrawer" title="更多筛选" :width="300">
      <div class="flex flex-col gap-12px">
        <div>
          <div class="text-13px mb-6px">存储位置</div>
          <Select v-model:value="sourceFilter" style="width: 100%" @change="load(true)">
            <Select.Option value="all">全部</Select.Option>
            <Select.Option value="local">车端本地</Select.Option>
            <Select.Option value="cloud">云端</Select.Option>
          </Select>
        </div>
        <Button @click="vehicles.refreshDetail(vehicleId)"><ReloadOutlined /> 刷新车辆状态</Button>
      </div>
    </Drawer>

    <!-- 删除确认 -->
    <Modal v-model:open="deleteOpen" title="删除录像" :footer="null">
      <template v-if="!job">
        <Alert type="warning" show-icon class="mb-10px"
          :message="`将删除所选 ${selected.size} 段录像`"
          :description="`同时删除四路视频、车辆数据、事件与缩略图。范围：${scope === 'cloud' ? '仅云端副本（车端原片保留）' : '车端与云端'}。${lockedSelected ? `${lockedSelected} 段已锁定，将被跳过（需先在车端解锁）。` : ''}`" />
        <Radio.Group v-model:value="scope" class="mb-12px">
          <Radio value="cloud">仅删除云端</Radio>
          <Radio value="local">删除车端与云端</Radio>
        </Radio.Group>
        <div class="flex gap-8px justify-end">
          <Button @click="deleteOpen = false">取消</Button>
          <Button danger type="primary" :loading="deleting" @click="submitDelete()">下一步：重新认证</Button>
        </div>
      </template>
    </Modal>

    <ReauthModal v-model:open="reauthOpen" @ok="(t) => submitDelete(t)" />

    <!-- 删除作业回执 -->
    <Modal :open="!!job" title="删除作业回执" :footer="null" @cancel="job = null">
      <template v-if="job">
        <Alert v-if="job.state === 'partial_success'" type="warning" show-icon class="mb-10px"
          message="部分片段未能删除（逐项回执如下），刷新后列表已同步。" />
        <Alert v-else-if="job.state === 'success'" type="success" show-icon class="mb-10px" message="全部删除成功" />
        <Alert v-else-if="job.state === 'failed'" type="error" show-icon class="mb-10px" :message="job.reason ?? '作业失败'" />
        <div v-else class="text-13px mb-10px">作业状态：{{ job.state }}</div>
        <div v-if="job.items" class="flex flex-col gap-4px max-h-300px overflow-auto">
          <div v-for="it in job.items" :key="it.ref" class="flex items-center justify-between text-13px px-8px py-4px rounded-8px"
            style="background: var(--ar-bg)">
            <span class="tabular-nums">{{ clipStartText(it.ref) }}</span>
            <Tag :color="itemStateColor[it.state]">{{ itemStateText[it.state] }}<template v-if="it.reason"> · {{ it.reason }}</template></Tag>
          </div>
        </div>
        <div class="text-right mt-10px"><Button @click="job = null">关闭</Button></div>
      </template>
    </Modal>
  </div>
</template>
