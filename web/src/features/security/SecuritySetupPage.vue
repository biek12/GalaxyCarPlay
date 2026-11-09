<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Alert, Button, Card, Checkbox, Input, message, Steps } from 'ant-design-vue'
import { api } from '@/api/client'
import QRCode from 'qrcode'
import { takeFactorTicket } from './factorTicket'

const router = useRouter()
const step = ref(0)
const otpauthUri = ref('')
const secret = ref('')
const enrollmentId = ref('')
const qrData = ref('')
const code = ref('')
const loading = ref(false)
const error = ref('')
const recoveryCodes = ref<string[]>([])
const savedChecked = ref(false)

onMounted(async () => {
  try {
    const ticket = takeFactorTicket()
    if (!ticket) { await router.replace('/security'); return }
    const r = await api.me.enrollMfa(ticket)
    otpauthUri.value = r.otpauthUri
    secret.value = r.secret
    enrollmentId.value = r.enrollmentId
    qrData.value = await QRCode.toDataURL(r.otpauthUri, { width: 220, margin: 1 })
    step.value = 0
  } catch (e: any) {
    error.value = e?.message ?? '创建两步验证失败'
  }
})

async function confirm() {
  if (!/^\d{6}$/.test(code.value)) { error.value = '请输入 6 位数字验证码'; return }
  loading.value = true
  error.value = ''
  try {
    const r = await api.me.confirmMfa(enrollmentId.value, code.value)
    recoveryCodes.value = r.recoveryCodes
    step.value = 2
  } catch (e: any) {
    error.value = e?.message ?? '验证码错误，未确认的两步验证不会启用'
  } finally {
    loading.value = false
  }
}

function finish() {
  router.push('/vehicles')
}
</script>

<template>
  <div class="min-h-100dvh flex items-center justify-center p-16px" :style="{ background: 'var(--ar-bg)' }">
    <Card class="ar-card w-full max-w-460px" title="更换验证器">
      <Alert v-if="error" type="error" :message="error" show-icon class="mb-12px" />
      <Steps :current="step" size="small" :items="[{ title: '扫码' }, { title: '验证' }, { title: '恢复码' }]" class="mb-16px" />

      <template v-if="step === 0">
        <Alert type="info" show-icon message="用验证器 App（如 Google Authenticator）扫描二维码，或手动输入密钥。" class="mb-12px" />
        <div class="text-center">
          <img v-if="qrData" :src="qrData" alt="TOTP 二维码" class="rounded-8px" style="background:#fff;padding:8px" />
          <div class="mt-8px text-13px select-all" style="color: var(--ar-text-2)">密钥：{{ secret }}</div>
        </div>
        <Button type="primary" block class="mt-16px" @click="step = 1">我已添加，下一步</Button>
      </template>

      <template v-else-if="step === 1">
        <Input v-model:value="code" size="large" placeholder="输入 6 位验证码" :inputmode="'numeric'" @press-enter="confirm" />
        <Alert v-if="error" type="error" :message="error" show-icon class="mt-12px" />
        <div class="text-12px mt-8px" style="color: var(--ar-text-2)">
          只有验证通过才会启用；单次登录 5 分钟过期，同一时间步不能重复使用。
        </div>
        <Button type="primary" block class="mt-12px" :loading="loading" @click="confirm">确认启用</Button>
      </template>

      <template v-else>
        <Alert type="warning" show-icon
          message="恢复码只展示这一次，请立即保存到安全的地方" class="mb-12px" />
        <div class="grid grid-cols-2 gap-4px select-all">
          <div v-for="c in recoveryCodes" :key="c" class="ar-chip justify-center tabular-nums"
            style="background: var(--ar-bg)">{{ c }}</div>
        </div>
        <Checkbox v-model:checked="savedChecked" class="mt-12px">我已将恢复码保存到安全位置</Checkbox>
        <Button type="primary" block class="mt-12px" :disabled="!savedChecked" @click="finish">完成设置</Button>
        <div class="text-12px mt-8px" style="color: var(--ar-text-2)">
          刷新本页不会再次显示恢复码；如未保存，可稍后在「安全设置」重新生成（旧组全部失效）。
        </div>
      </template>
    </Card>
  </div>
</template>
