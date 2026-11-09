<script setup lang="ts">
import { ref } from 'vue'
import { Modal, Input, message } from 'ant-design-vue'
import { SafetyOutlined } from '@ant-design/icons-vue'
import { api } from '@/api/client'

const emit = defineEmits<{ ok: [ticket: string] }>()
const open = defineModel<boolean>('open', { default: false })
const code = ref('')
const loading = ref(false)

async function submit() {
  if (!code.value) return
  loading.value = true
  try {
    const r = await api.auth.reauth(code.value)
    code.value = ''
    open.value = false
    emit('ok', r.actionTicket)
  } catch (e: any) {
    message.error(e?.message ?? '重新认证失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Modal v-model:open="open" title="重新认证" :confirm-loading="loading" ok-text="确认" cancel-text="取消" @ok="submit">
    <div class="text-13px mb-12px" style="color: var(--ar-text-2)">
      敏感操作前需要输入验证器当前的 6 位验证码（免密码体系），凭据 5 分钟内有效。
    </div>
    <Input v-model:value="code" placeholder="6 位验证码" size="large" inputmode="numeric" :maxlength="6" @press-enter="submit">
      <template #prefix><SafetyOutlined /></template>
    </Input>
  </Modal>
</template>
