<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Alert, Button, Card, List, Modal, Tag, message } from 'ant-design-vue'
import { api } from '@/api/client'
import { useVehiclesStore } from '@/stores/vehicles'
import { fmtDateTime } from '@/utils/format'
import ReauthModal from '@/features/security/ReauthModal.vue'

const route = useRoute()
const router = useRouter()
const vehicles = useVehiclesStore()

const vehicleId = computed(() => route.params.id as string)
const access = ref<Awaited<ReturnType<typeof api.vehicles.access>> | null>(null)
const reauthOpen = ref(false)
const reauthAction = ref<'' | 'remove' | 'unbind'>('')
const removeUserId = ref('')

onMounted(load)
async function load() {
  access.value = await api.vehicles.access(vehicleId.value).catch(() => null)
}

const isOwner = computed(() => access.value?.role === 'owner')

function askRemove(userId: string) {
  removeUserId.value = userId
  reauthAction.value = 'remove'
  reauthOpen.value = true
}
function askUnbind() {
  reauthAction.value = 'unbind'
  reauthOpen.value = true
}
async function handleTicket(ticket: string) {
  try {
    if (reauthAction.value === 'remove') {
      await api.vehicles.removeMember(vehicleId.value, removeUserId.value, ticket)
      message.success('已撤销该成员的观看授权（立即生效）')
    } else if (reauthAction.value === 'unbind') {
      await api.vehicles.unbind(vehicleId.value, ticket)
      message.success('车辆已解绑')
      await vehicles.loadList(true)
      router.push('/vehicles')
      return
    }
    load()
  } catch (e: any) {
    message.error(e?.message ?? '操作失败')
  }
}
function confirmUnbind() {
  Modal.confirm({
    title: '解绑车辆？',
    content: '解绑后本账号将失去观看与录像访问；车端绑定关系同时撤销。',
    okText: '确认解绑', okType: 'danger', cancelText: '取消',
    onOk: askUnbind,
  })
}
</script>

<template>
  <div class="max-w-720px mx-auto flex flex-col gap-16px">
    <h2 class="text-18px font-600 m-0">归属与授权</h2>

    <Card v-if="access" class="ar-card" title="归属">
      <div class="text-13px">
        车主：{{ access.ownerName }} · 我的车端角色：
        <Tag :color="isOwner ? 'green' : 'blue'">{{ isOwner ? '车主' : '观看者' }}</Tag>
      </div>
      <Alert v-if="!isOwner" class="mt-10px" type="info" show-icon
        message="观看者仅可查看实时画面、录像库与回放；不能删除录像、更改哨兵或管理成员。" />
    </Card>

    <Card class="ar-card" title="授权成员">
      <List :data-source="access?.members ?? []" size="small">
        <template #renderItem="{ item }">
          <List.Item>
            <div class="flex-1">
              <div>{{ item.displayName }} <Tag color="blue">观看者</Tag></div>
              <div class="text-12px" style="color: var(--ar-text-2)">授权时间 {{ fmtDateTime(item.grantedAt) }}</div>
            </div>
            <Button v-if="isOwner" size="small" danger @click="askRemove(item.userId)">移除</Button>
          </List.Item>
        </template>
      </List>
      <div v-if="access && access.members.length === 0" class="text-13px" style="color: var(--ar-text-2)">暂无其他授权成员</div>
    </Card>

    <Card v-if="isOwner" class="ar-card" title="解绑车辆">
      <div class="text-13px mb-10px" style="color: var(--ar-text-2)">
        解绑会立即终止所有观看会话并撤销对象下载授权；转移归属需在车端重新验证。
      </div>
      <Button danger @click="confirmUnbind">解绑该车辆</Button>
    </Card>

    <ReauthModal v-model:open="reauthOpen" @ok="handleTicket" />
  </div>
</template>
