<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { VideoCameraOutlined, HistoryOutlined, SafetyOutlined, CarOutlined } from '@ant-design/icons-vue'

// 仅在车辆上下文（路由带 :id）显示；主页 /vehicles 不显示
const route = useRoute()
const router = useRouter()
const vid = computed(() => route.params.id as string)
const show = computed(() => !!vid.value)

const tabs = computed(() => [
  { key: 'live', label: '实时', icon: VideoCameraOutlined, path: `/vehicles/${vid.value}/live`, active: route.name === 'live' },
  { key: 'history', label: '录像库', icon: HistoryOutlined, path: `/vehicles/${vid.value}/history`, active: ['history', 'clip'].includes(route.name as string) },
  { key: 'sentry', label: '哨兵', icon: SafetyOutlined, path: `/vehicles/${vid.value}/sentry`, active: route.name === 'sentry' },
  { key: 'vehicle', label: '车辆', icon: CarOutlined, path: `/vehicles/${vid.value}`, active: ['vehicle-detail', 'access'].includes(route.name as string) },
])
</script>

<template>
  <nav v-if="show" class="vehicle-tabbar">
    <button v-for="t in tabs" :key="t.key" class="tab-item" :class="{ active: t.active }" @click="router.push(t.path)">
      <component :is="t.icon" class="tab-icon" />
      <span>{{ t.label }}</span>
    </button>
  </nav>
</template>

<style scoped>
.vehicle-tabbar {
  position: fixed;
  left: 0; right: 0; bottom: 0;
  z-index: 30;
  display: flex;
  height: calc(56px + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  background: var(--ar-panel);
  border-top: 1px solid var(--ar-line);
}
.tab-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  background: none;
  border: none;
  color: var(--ar-text-2);
  font-size: 11px;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.tab-icon { font-size: 19px; }
.tab-item.active { color: var(--ar-accent); font-weight: 600; }
.tab-item:active { opacity: 0.7; }

/* 桌面端：底部居中悬浮胶囊 */
@media (min-width: 768px) {
  .vehicle-tabbar {
    left: 50%;
    right: auto;
    transform: translateX(-50%);
    bottom: 16px;
    height: 52px;
    padding-bottom: 0;
    width: 420px;
    border: 1px solid var(--ar-line);
    border-radius: 999px;
    box-shadow: 0 10px 28px rgba(0, 0, 0, 0.16);
    overflow: hidden;
  }
}
</style>
