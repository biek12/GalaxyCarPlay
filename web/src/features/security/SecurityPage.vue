<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Alert, Button, Card, List, Modal, message, Space, Switch, Tag } from 'ant-design-vue'
import { api } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { fmtDateTime } from '@/utils/format'
import ReauthModal from './ReauthModal.vue'
import { setFactorTicket } from './factorTicket'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const sessions = ref<Awaited<ReturnType<typeof api.me.listSessions>>>([])
const reauthOpen = ref(false)
const reauthAction = ref<'' | 'mfa' | 'recovery'>('')
const newCodes = ref<string[]>([])
const codesOpen = ref(false)

async function loadSessions() {
  sessions.value = await api.me.listSessions()
}
onMounted(loadSessions)

function askReauth(action: typeof reauthAction.value) {
  reauthAction.value = action
  reauthOpen.value = true
}

async function handleTicket(ticket: string) {
  try {
    if (reauthAction.value === 'mfa') {
      setFactorTicket(ticket)
      await router.push('/security/setup')
    } else if (reauthAction.value === 'recovery') {
      const r = await api.me.regenerateRecoveryCodes(ticket)
      newCodes.value = r.recoveryCodes
      codesOpen.value = true
    }
  } catch (e: any) {
    message.error(e?.message ?? '操作失败')
  }
}

function replaceMfa() { askReauth('mfa') }
function regenCodes() {
  askReauth('recovery')
}

async function revoke(id: string) {
  try {
    const r = await api.me.revokeSession(id)
    if (r.revokedCurrent) {
      await auth.logout()
      router.push('/login')
      return
    }
    message.success('会话已撤销')
    loadSessions()
  } catch (e: any) {
    message.error(e?.message ?? '撤销失败')
  }
}
async function revokeAll() {
  await api.me.revokeAll()
  message.success('其他会话已全部撤销')
  loadSessions()
}
</script>

<template>
  <div class="max-w-720px mx-auto flex flex-col gap-16px">
    <h2 class="text-18px font-600 m-0">安全设置</h2>

    <Alert v-if="route.query.mfa === 'required'" type="warning" show-icon
      message="账号尚未完成两步验证：启用后才能绑定或观看车辆。" />

    <Card class="ar-card" title="两步验证（TOTP）">
      <div class="flex items-center justify-between">
        <div class="text-13px" style="color: var(--ar-text-2)">当前状态 · 登录与敏感操作均使用 TOTP 验证码</div>
        <Switch :checked="auth.mfaEnabled" disabled />
      </div>
      <Space class="mt-10px">
        <Button v-if="!auth.mfaEnabled" type="primary" @click="router.push('/security/setup')">启用两步验证</Button>
        <Button v-else @click="replaceMfa">更换验证器</Button>
        <Button @click="regenCodes">重新生成恢复码</Button>
      </Space>
    </Card>

    <Card class="ar-card" title="登录会话">
      <List :data-source="sessions" size="small">
        <template #renderItem="{ item }">
          <List.Item>
            <div class="flex-1">
              <div>{{ item.device }} <Tag v-if="item.current" color="green">当前</Tag></div>
              <div class="text-12px" style="color: var(--ar-text-2)">{{ item.ipMasked }} · 最近活跃 {{ fmtDateTime(item.lastActiveAt) }}</div>
            </div>
            <Button size="small" danger :disabled="item.current" @click="revoke(item.id)">撤销</Button>
          </List.Item>
        </template>
      </List>
      <Button class="mt-8px" danger @click="revokeAll">撤销所有其他会话</Button>
    </Card>

    <ReauthModal v-model:open="reauthOpen" @ok="handleTicket" />

    <Modal v-model:open="codesOpen" title="新的恢复码（旧组已全部失效）" :footer="null">
      <div class="grid grid-cols-2 gap-4px select-all">
        <div v-for="c in newCodes" :key="c" class="ar-chip justify-center tabular-nums" style="background: var(--ar-bg)">{{ c }}</div>
      </div>
    </Modal>
  </div>
</template>
