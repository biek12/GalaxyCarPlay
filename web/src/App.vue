<script setup lang="ts">
import { computed } from 'vue'
import { ConfigProvider } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const themeCfg = computed(() => ({
  token: {
    colorPrimary: '#00b96b',
    borderRadius: 8,
    ...(appStore.isDark
      ? { colorBgBase: '#0f1216', colorTextBase: '#e6e9ee', colorBgContainer: '#14171d', colorBgElevated: '#1a1e26' }
      : {}),
  },
  algorithm: appStore.isDark ? undefined : undefined,
}))
</script>

<template>
  <ConfigProvider :theme="themeCfg" :locale="zhCN">
    <RouterView />
  </ConfigProvider>
</template>
