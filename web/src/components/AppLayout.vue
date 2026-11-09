<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Dropdown, Menu, Tooltip } from 'ant-design-vue'
import {
  UserOutlined, BulbOutlined, HighlightOutlined, SettingOutlined, LogoutOutlined,
} from '@ant-design/icons-vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useVehiclesStore } from '@/stores/vehicles'
import { usePlaybackStore } from '@/stores/playback'
import { api } from '@/api/client'
import MockScenarioPanel from '@/features/mock/MockScenarioPanel.vue'
import VehicleTabBar from '@/components/VehicleTabBar.vue'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const auth = useAuthStore()
const vehicles = useVehiclesStore()
const playback = usePlaybackStore()

const unsub = ref<null | (() => void)>(null)

onMounted(async () => {
  if (auth.loggedIn || hasTokenCookie()) await vehicles.loadList(true)
  unsub.value = api.events.subscribe((e) => {
    if (e.type === 'presence' && e.vehicleId) vehicles.refreshDetail(e.vehicleId).catch(() => {})
  })
})
function hasTokenCookie() { return !!localStorage.getItem('ar_web_token') }
onBeforeUnmount(() => unsub.value?.())

// 切换车辆时清理回放缓存
watch(() => route.params.id, (n, o) => {
  if (n && o && n !== o) playback.clear()
})

async function doLogout() {
  await auth.logout()
  router.push('/login')
}
const themeIcon = computed(() => (appStore.isDark ? HighlightOutlined : BulbOutlined))
function cycleTheme() {
  appStore.setTheme(appStore.theme === 'dark' ? 'light' : 'dark')
}
// 车辆上下文页面为底部 TabBar 预留空间
const inVehicle = computed(() => !!route.params.id)
</script>

<template>
  <div class="min-h-100dvh flex flex-col" :style="{ background: 'var(--ar-bg)' }">
    <!-- 顶栏 -->
    <header class="flex items-center gap-10px px-14px h-54px sticky top-0 z-20"
      :style="{ background: 'var(--ar-panel)', borderBottom: '1px solid var(--ar-line)' }">
      <div class="flex items-center gap-8px font-600 text-16px cursor-pointer shrink-0" @click="router.push('/vehicles')">
        <span class="w-20px h-20px rounded-full inline-block" :style="{ background: 'var(--ar-accent)' }" />
        AutoRecord
      </div>

      <div class="flex-1" />

      <Tooltip :title="appStore.theme === 'system' ? '跟随系统' : appStore.theme === 'dark' ? '深色' : '浅色'">
        <button class="text-16px px-6px py-6px rounded-8px shrink-0" :style="{ color: 'var(--ar-text-2)' }" @click="cycleTheme">
          <component :is="themeIcon" />
        </button>
      </Tooltip>
      <Dropdown placement="bottomRight" overlay-class-name="ar-user-menu">
        <button class="w-32px h-32px rounded-full flex items-center justify-center shrink-0"
          :style="{ background: 'var(--ar-bg)', color: 'var(--ar-text)' }">
          <UserOutlined />
        </button>
        <template #overlay>
          <Menu>
            <Menu.Item key="sec" @click="router.push('/security')"><SettingOutlined /> 安全设置</Menu.Item>
            <Menu.Item key="out" @click="doLogout"><LogoutOutlined /> 退出登录</Menu.Item>
          </Menu>
        </template>
      </Dropdown>
    </header>

    <!-- 内容区：车辆上下文为底部 TabBar 预留高度 -->
    <main class="flex-1 min-w-0 p-12px md:p-16px" :class="inVehicle ? 'pb-84px md:pb-84px' : 'pb-16px'">
      <RouterView />
    </main>

    <!-- 车辆上下文 TabBar（主页不显示） -->
    <VehicleTabBar />

    <!-- 悬浮按钮：车辆切换卡片 + 设置 -->
    <MockScenarioPanel />
  </div>
</template>

<style>
/* 用户下拉：防止窄屏内文字竖排 */
.ar-user-menu {
  min-width: 150px !important;
}
.ar-user-menu .ant-dropdown-menu-item {
  white-space: nowrap;
}
</style>
