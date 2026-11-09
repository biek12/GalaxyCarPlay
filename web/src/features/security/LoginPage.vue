<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Alert, Button, Card, Input, Modal, message } from 'ant-design-vue'
import { MobileOutlined, SafetyOutlined, ScanOutlined } from '@ant-design/icons-vue'
import QRCode from 'qrcode'
import { useAuthStore } from '@/stores/auth'
import { DEMO_PHONE } from '@/api/mock/server'
import { api } from '@/api/client'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const phone = ref(api.mock ? DEMO_PHONE : '')
const step = ref<'phone' | 'activation' | 'bind' | 'totp'>('phone')
const code = ref('')
const invitationCode = ref('')
const useRecovery = ref(false)
const loading = ref(false)
const error = ref('')
const qrData = ref('')
const secret = ref('')
const recoveryCodes = ref<string[]>([])
const codesOpen = ref(false)

async function next() {
  if (!/^\d{11}$/.test(phone.value)) { error.value = '请输入 11 位手机号'; return }
  loading.value = true
  error.value = ''
  try {
    const r = await auth.login(phone.value)
    if (r.next === 'enroll') {
      invitationCode.value = ''
      step.value = 'activation'
    } else {
      step.value = 'totp'
    }
  } catch (e: any) {
    error.value = e?.message ?? '登录失败'
  } finally {
    loading.value = false
  }
}

async function activate() {
  if (!invitationCode.value.trim()) { error.value = '请输入首次激活码'; return }
  loading.value = true
  error.value = ''
  try {
    const enr = await api.auth.enrollChallenge(auth.pendingChallengeId, invitationCode.value.trim())
    secret.value = enr.secret
    qrData.value = await QRCode.toDataURL(enr.otpauthUri, { width: 220, margin: 1 })
    invitationCode.value = ''
    step.value = 'bind'
  } catch (e: any) {
    error.value = e?.message ?? '激活失败，请检查激活码或返回重新输入手机号'
  } finally {
    loading.value = false
  }
}

async function doVerify() {
  loading.value = true
  error.value = ''
  try {
    const r = await auth.verify(useRecovery.value ? { recoveryCode: code.value } : { code: code.value })
    if (r.recoveryCodes?.length) {
      recoveryCodes.value = r.recoveryCodes
      codesOpen.value = true
    } else {
      message.success('登录成功')
      router.push((route.query.redirect as string) || '/vehicles')
    }
  } catch (e: any) {
    error.value = e?.message ?? '验证失败'
  } finally {
    loading.value = false
  }
}

function finishBind() {
  codesOpen.value = false
  message.success('登录成功')
  router.push((route.query.redirect as string) || '/vehicles')
}
</script>

<template>
  <div class="min-h-100dvh flex items-center justify-center p-16px" :style="{ background: 'var(--ar-bg)' }">
    <div class="w-full max-w-420px">
      <div class="text-center mb-20px">
        <span class="w-40px h-40px rounded-full inline-block mb-10px" :style="{ background: 'var(--ar-accent)' }" />
        <div class="text-20px font-600">AutoRecord 在线查看</div>
        <div class="text-13px mt-4px" style="color: var(--ar-text-2)">手机号 + 两步验证（免密码）</div>
      </div>

      <Card class="ar-card">
        <!-- 第一步：手机号 -->
        <template v-if="step === 'phone'">
          <div class="flex flex-col gap-12px">
            <Input v-model:value="phone" size="large" placeholder="手机号" inputmode="numeric" :maxlength="11" @press-enter="next">
              <template #prefix><MobileOutlined /></template>
            </Input>
            <Alert v-if="error" type="error" :message="error" show-icon />
            <Button type="primary" size="large" :loading="loading" @click="next">下一步</Button>
          </div>
        </template>

        <template v-else-if="step === 'activation'">
          <div class="flex flex-col gap-12px">
            <Alert type="info" show-icon message="首次激活账号" description="该手机号尚未绑定验证器。请填写管理员提供的一次性激活码，验证后即可扫码绑定。" />
            <div class="text-13px" style="color: var(--ar-text-2)">手机号 · {{ phone }}</div>
            <label for="activation-code" class="text-13px">激活码</label>
            <Input id="activation-code" v-model:value="invitationCode" size="large" placeholder="请输入一次性激活码" autocomplete="off" :maxlength="128" @press-enter="activate" />
            <Alert v-if="error" type="error" :message="error" show-icon />
            <Button type="primary" size="large" :loading="loading" @click="activate">验证激活码</Button>
            <a class="text-13px text-center" style="color: var(--ar-accent)" @click="step = 'phone'; error = ''">← 返回手机号</a>
          </div>
        </template>

        <!-- 第二步 A：绑定两步验证（首次登录） -->
        <template v-else-if="step === 'bind'">
          <div class="flex flex-col gap-12px">
            <Alert type="info" show-icon message="首次登录：用验证器 App 扫码绑定，或手动输入密钥" />
            <div class="text-center">
              <img v-if="qrData" :src="qrData" alt="TOTP 二维码" class="rounded-8px" style="background:#fff;padding:8px" />
              <div class="mt-8px text-12px select-all break-all" style="color: var(--ar-text-2)">密钥：{{ secret }}</div>
            </div>
            <Input v-model:value="code" size="large" placeholder="输入 App 上的 6 位验证码" inputmode="numeric" :maxlength="6" @press-enter="doVerify">
              <template #prefix><SafetyOutlined /></template>
            </Input>
            <Alert v-if="error" type="error" :message="error" show-icon />
            <Button type="primary" size="large" :loading="loading" @click="doVerify"><ScanOutlined /> 确认绑定并登录</Button>
            <a class="text-13px text-center" style="color: var(--ar-accent)" @click="step = 'phone'">← 返回</a>
          </div>
        </template>

        <!-- 第二步 B：已绑定，输入验证码 -->
        <template v-else>
          <div class="flex flex-col gap-12px">
            <Alert type="info" show-icon message="输入验证器 App 的 6 位验证码" />
            <Input v-model:value="code" size="large" :placeholder="useRecovery ? '恢复码（xxxxx-xxxxx）' : '6 位验证码'"
              :inputmode="useRecovery ? 'text' : 'numeric'" @press-enter="doVerify">
              <template #prefix><SafetyOutlined /></template>
            </Input>
            <a class="text-13px" style="color: var(--ar-accent)" @click="useRecovery = !useRecovery">
              {{ useRecovery ? '改用验证码' : '改用恢复码登录' }}
            </a>
            <Alert v-if="error" type="error" :message="error" show-icon />
            <Button type="primary" size="large" :loading="loading" @click="doVerify">登录</Button>
            <a class="text-13px text-center" style="color: var(--ar-accent)" @click="step = 'phone'">← 返回</a>
          </div>
        </template>
      </Card>
    </div>

    <!-- 首次绑定成功：恢复码一次性展示 -->
    <Modal v-model:open="codesOpen" title="两步验证已启用 · 恢复码只展示这一次" :footer="null">
      <div class="grid grid-cols-2 gap-4px select-all mb-12px">
        <div v-for="c in recoveryCodes" :key="c" class="ar-chip justify-center tabular-nums" style="background: var(--ar-bg)">{{ c }}</div>
      </div>
      <Button type="primary" block @click="finishBind">我已保存，进入应用</Button>
    </Modal>
  </div>
</template>
