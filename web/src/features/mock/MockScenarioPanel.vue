<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Divider, Drawer, Switch, Button, Space, message } from 'ant-design-vue'
import {
  SettingOutlined, ReloadOutlined, HomeOutlined, CarOutlined, CheckOutlined,
} from '@ant-design/icons-vue'
import { loadScenarios, saveScenarios, resetMfa, type Scenarios } from '@/api/mock/server'
import { useVehiclesStore } from '@/stores/vehicles'
import { PRESENCE_COLOR, PRESENCE_LABEL } from '@/utils/presence'
import { api } from '@/api/client'

const open = ref(false)
const scenarios = ref<Scenarios>(loadScenarios())
const vehicles = useVehiclesStore()
const router = useRouter()
const route = useRoute()

function set<K extends keyof Scenarios>(key: K, v: Scenarios[K]) {
  scenarios.value[key] = v
  saveScenarios(scenarios.value)
}
function reset() {
  scenarios.value = loadScenarios()
  saveScenarios(scenarios.value)
  vehicles.loadList(true)
}
function doResetMfa() {
  resetMfa()
  message.success('已重置两步验证，重新登录将进入扫码绑定流程')
}
function switchVehicle(id: string) {
  vehicles.setActive(id)
  router.push(`/vehicles/${id}`)
  open.value = false
}
function goHome() {
  router.push('/vehicles')
  open.value = false
}
function isCurrent(id: string) {
  return (route.params.id as string) === id
}
</script>

<template>
  <div>
    <button
      class="fixed right-16px bottom-84px md:bottom-84px z-40 w-42px h-42px rounded-full text-17px flex items-center justify-center shadow-lg"
      style="background: var(--ar-panel); border: 1px solid var(--ar-line); color: var(--ar-text)"
      title="车辆切换与设置"
      @click="open = true; vehicles.loadList()">
      <SettingOutlined />
    </button>
    <Drawer v-model:open="open" title="车辆与设置" :width="320" placement="right">
      <!-- 车辆卡片列表：点选切换 / 回主页 -->
      <div class="text-13px font-600 mb-8px">切换车辆</div>
      <div class="flex flex-col gap-8px">
        <button v-for="v in vehicles.list" :key="v.vehicleId"
          class="flex items-center gap-10px px-12px py-10px rounded-12px text-left w-full"
          :style="{
            background: isCurrent(v.vehicleId) ? 'color-mix(in srgb, var(--ar-accent) 12%, var(--ar-panel))' : 'var(--ar-bg)',
            border: isCurrent(v.vehicleId) ? '1.5px solid var(--ar-accent)' : '1px solid var(--ar-line)',
          }"
          @click="switchVehicle(v.vehicleId)">
          <i class="presence-dot shrink-0" :style="{ background: PRESENCE_COLOR[v.presence.state] }" />
          <span class="flex-1 min-w-0">
            <span class="block text-14px font-600 truncate">{{ v.name }}</span>
            <span class="block text-12px" style="color: var(--ar-text-2)">
              {{ PRESENCE_LABEL[v.presence.state] }} · {{ v.role === 'owner' ? '车主' : '观看者' }}
            </span>
          </span>
          <CheckOutlined v-if="isCurrent(v.vehicleId)" style="color: var(--ar-accent)" />
        </button>
        <Button block class="mt-4px" @click="goHome">
          <HomeOutlined /> 回到我的车辆（主页）
        </Button>
      </div>

      <template v-if="api.mock">
        <Divider />
        <div class="text-13px font-600 mb-8px">Mock 场景（仅演示）</div>
        <div class="text-12px mb-10px" style="color: var(--ar-text-2)">
          覆盖交接文档 §7 异常场景；开关即时生效并推送事件。
        </div>
        <div class="flex flex-col gap-12px">
          <div class="flex items-center justify-between gap-12px">
            <span class="text-13px">L6 在线 / 休眠</span>
            <Switch size="small" :checked="scenarios.l6Online" @change="(v: any) => set('l6Online', !!v)" />
          </div>
          <div class="flex items-center justify-between gap-12px">
            <span class="text-13px">右后断流（缺路）</span>
            <Switch size="small" :checked="scenarios.rearLost" @change="(v: any) => set('rearLost', !!v)" />
          </div>
          <div class="flex items-center justify-between gap-12px">
            <span class="text-13px">左侧摄像头被原厂占用</span>
            <Switch size="small" :checked="scenarios.leftOccupied" @change="(v: any) => set('leftOccupied', !!v)" />
          </div>
          <div class="flex items-center justify-between gap-12px">
            <span class="text-13px">远程唤醒失败</span>
            <Switch size="small" :checked="scenarios.wakeFail" @change="(v: any) => set('wakeFail', !!v)" />
          </div>
          <div class="flex items-center justify-between gap-12px">
            <span class="text-13px">平面模式标定可用</span>
            <Switch size="small" :checked="scenarios.flatCalib" @change="(v: any) => set('flatCalib', !!v)" />
          </div>
          <div class="flex items-center justify-between gap-12px">
            <span class="text-13px">实时画面卡顿</span>
            <Switch size="small" :checked="scenarios.streamStall" @change="(v: any) => set('streamStall', !!v)" />
          </div>
          <div class="flex items-center justify-between gap-12px">
            <span class="text-13px">批量删除部分失败</span>
            <Switch size="small" :checked="scenarios.deletePartialFail" @change="(v: any) => set('deletePartialFail', !!v)" />
          </div>
          <div class="flex items-center justify-between gap-12px">
            <span class="text-13px">分页期间插入新片段</span>
            <Switch size="small" :checked="scenarios.insertNewClip" @change="(v: any) => set('insertNewClip', !!v)" />
          </div>
          <div class="flex items-center justify-between gap-12px">
            <span class="text-13px">哨兵布防失败</span>
            <Switch size="small" :checked="scenarios.sentryFail" @change="(v: any) => set('sentryFail', !!v)" />
          </div>
        </div>
        <Space class="mt-12px">
          <Button size="small" @click="vehicles.loadList(true)"><ReloadOutlined /> 刷新车辆</Button>
          <Button size="small" @click="reset">恢复默认</Button>
        </Space>
        <div class="mt-12px flex flex-col gap-4px">
          <Button size="small" @click="doResetMfa">重置两步验证（回到扫码绑定）</Button>
          <div class="text-12px" style="color: var(--ar-text-2)">绑定码：BIND-DEMO-OK（4 秒后车端确认）、BIND-DEMO-EXPIRED</div>
        </div>
      </template>
      <template v-else>
        <Divider />
        <div class="text-12px" style="color: var(--ar-text-2)">
          <CarOutlined /> 生产模式：车辆列表来自 /web/v1。
        </div>
      </template>
    </Drawer>
  </div>
</template>
