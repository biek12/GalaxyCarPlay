<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Alert, Button, Card, Input, Modal, Spin, Tag, message } from 'ant-design-vue'
import { PlusOutlined, VideoCameraOutlined, HistoryOutlined } from '@ant-design/icons-vue'
import { useVehiclesStore } from '@/stores/vehicles'
import { PRESENCE_COLOR, PRESENCE_LABEL } from '@/utils/presence'
import { fmtRelTime, fmtBytes } from '@/utils/format'
import { api } from '@/api/client'

import BindingScanner from './BindingScanner.vue'
import { parseBindingCode } from './bindingCode'
const scanning = ref(false)
const route = useRoute()
const router = useRouter()
const vehicles = useVehiclesStore()

const bindOpen = ref(false)
const bindCode = ref('')
const binding = ref<{ id: string; status: string; reason?: string } | null>(null)
let bindTimer = 0

onMounted(async () => {
  await vehicles.loadList(true)
  const saved = sessionStorage.getItem('ar_pending_binding')
  sessionStorage.removeItem('ar_pending_binding')
  if (saved) {
    try { const pending = JSON.parse(saved); if (pending.until > Date.now()) { bindCode.value = parseBindingCode(pending.code); bindOpen.value = true } } catch { /* 损坏或过期的一次性材料直接丢弃 */ }
  }
  if (route.hash.startsWith('#bind=')) {
    try { bindCode.value = parseBindingCode(window.location.href); bindOpen.value = true } catch (e: any) { message.error(e.message) }
    // 一次性材料不留在地址栏或后续导航中。
    await router.replace({ path: route.path, query: route.query, hash: '' })
  }
})
onBeforeUnmount(() => clearTimeout(bindTimer))
function closeBind() { clearTimeout(bindTimer); scanning.value = false; binding.value = null }
function scanned(code: string) { bindCode.value = code; scanning.value = false; message.success('已识别车辆绑定码，请提交并在车端确认') }

function go(id: string, suffix = '') {
  vehicles.setActive(id)
  router.push(`/vehicles/${id}${suffix}`)
}

async function openBind() {
  closeBind()
  bindOpen.value = true
  bindCode.value = ''
  binding.value = null
}

async function submitBind() {
  if (!bindCode.value.trim()) return
  try {
    scanning.value = false
    clearTimeout(bindTimer)
    const r = await api.vehicles.bind(api.mock ? bindCode.value.trim() : parseBindingCode(bindCode.value))
    binding.value = { id: r.bindingId, status: r.status, reason: r.reason }
    bindTimer = window.setTimeout(pollBind, 1500)
  } catch (e: any) {
    message.error(e?.message ?? '提交绑定码失败')
  }
}

async function pollBind() {
  if (!binding.value) return
  const id = binding.value.id
  let r
  try { r = await api.vehicles.binding(id) } catch (e: any) {
    if (bindOpen.value) { message.error(e?.message ?? '查询绑定状态失败'); bindTimer = window.setTimeout(pollBind, 3000) }
    return
  }
  if (!bindOpen.value || binding.value?.id !== id) return
  if (r.status === 'pending_device_confirmation') { bindTimer = window.setTimeout(pollBind, 1500); return }
  {
    clearTimeout(bindTimer)
    binding.value = { ...binding.value, status: r.status, reason: r.reason }
    if (r.status === 'confirmed') {
      message.success('车辆已绑定')
      await vehicles.loadList(true)
      setTimeout(() => { bindOpen.value = false; go(r.vehicleId!) }, 600)
    }
  }
}
</script>

<template>
  <div class="max-w-880px mx-auto">
    <div class="flex items-center justify-between mb-14px">
      <h2 class="text-18px font-600 m-0">我的车辆</h2>
      <Button type="primary" @click="openBind"><PlusOutlined /> 绑定车辆</Button>
    </div>

    <Spin :spinning="vehicles.loading">
      <div v-if="vehicles.list.length === 0" class="ar-panel p-32px text-center">
        <div class="text-15px mb-6px">还没有绑定车辆</div>
        <div class="text-13px mb-14px" style="color: var(--ar-text-2)">
          打开 AutoService 或 AutoRecord 的“车辆与账号”，申请绑定码。扫码或输入后，在车端确认即可长期绑定。
        </div>
        <Button type="primary" @click="openBind">去绑定</Button>
      </div>

      <div class="grid gap-12px" style="grid-template-columns: repeat(auto-fill, minmax(260px, 1fr))">
        <Card v-for="v in vehicles.list" :key="v.vehicleId" class="ar-card cursor-pointer hover:shadow-md"
          @click="go(v.vehicleId)">
          <div class="flex items-center gap-8px mb-6px">
            <i class="presence-dot" :style="{ background: PRESENCE_COLOR[v.presence.state] }" />
            <span class="font-600 text-15px">{{ v.name }}</span>
            <Tag :color="v.role === 'owner' ? 'green' : 'blue'" style="margin-left: auto">
              {{ v.role === 'owner' ? '车主' : '观看者' }}
            </Tag>
          </div>
          <div class="text-12px mb-8px" style="color: var(--ar-text-2)">
            {{ v.model }}<template v-if="v.plateMasked"> · {{ v.plateMasked }}</template>
            <div v-if="v.basicInfo" class="mt-4px">Android {{ v.basicInfo.androidVersion }} · AutoService {{ v.basicInfo.serviceVersion }}</div>
          </div>
          <div class="text-13px mb-4px">
            {{ PRESENCE_LABEL[v.presence.state] }}
            <span class="ar-text-2">· 最近在线 {{ fmtRelTime(v.presence.lastSeenAt) }}</span>
          </div>
          <div class="text-12px mb-10px" style="color: var(--ar-text-2)">
            {{ v.recording.state === 'recording' ? '正在录制' : '未在录制' }} · 存储 {{ fmtBytes(v.storage.usedBytes) }}/{{ fmtBytes(v.storage.quotaBytes) }}
          </div>
          <div class="flex gap-8px" @click.stop>
            <Button size="small" :disabled="v.presence.state !== 'online'"
              @click="go(v.vehicleId, '/live')"><VideoCameraOutlined /> 实时</Button>
            <Button size="small" @click="go(v.vehicleId, '/history')"><HistoryOutlined /> 录像库</Button>
          </div>
        </Card>
      </div>
    </Spin>

    <Modal v-model:open="bindOpen" title="绑定车辆" :footer="null" @cancel="closeBind">
      <template v-if="!binding || binding.status === 'pending_device_confirmation'">
        <div class="text-13px mb-10px" style="color: var(--ar-text-2)">
          绑定码 5 分钟有效、一次性使用。绑定成功后长期保留，直到主动取消绑定。
        </div>
        <Button block class="mb-12px" @click="scanning = !scanning">扫描车端二维码</Button>
        <BindingScanner :active="scanning && bindOpen" @code="scanned" @close="scanning = false" />
        <Input v-model:value="bindCode" size="large" placeholder="输入绑定码，或粘贴扫码得到的链接" @press-enter="submitBind" />
        <Button type="primary" block class="mt-10px" :disabled="!!binding" :loading="!!binding && binding.status === 'pending_device_confirmation'"
          @click="submitBind">提交绑定</Button>
        <div v-if="binding && binding.status === 'pending_device_confirmation'" class="mt-12px text-13px">
          已提交，等待车端确认目标账号…（车机会显示待确认账号）
        </div>
      </template>
      <template v-else-if="binding.status === 'confirmed'">
        <Alert type="success" show-icon message="绑定成功，正在进入车辆…" />
      </template>
      <template v-else>
        <Alert type="error" show-icon
          :message="binding.status === 'expired' ? '绑定码已过期' : '绑定被拒绝'"
          :description="binding.reason" />
        <Button block class="mt-10px" @click="binding = null">重新输入</Button>
      </template>
    </Modal>
  </div>
</template>
