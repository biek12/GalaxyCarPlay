<script setup lang="ts">
import { h, defineComponent, type Component, type PropType } from 'vue'
import {
  VideoCameraOutlined, HistoryOutlined, SafetyOutlined, CarOutlined,
  UserOutlined, SettingOutlined,
} from '@ant-design/icons-vue'

const props = defineProps({
  icon: { type: String as PropType<'video-camera' | 'history' | 'safety' | 'car' | 'user' | 'setting'>, required: true },
  label: { type: String, required: true },
  active: { type: Boolean, default: false },
})
const emit = defineEmits<{ click: [] }>()

const iconMap: Record<string, Component> = {
  'video-camera': VideoCameraOutlined, history: HistoryOutlined, safety: SafetyOutlined,
  car: CarOutlined, user: UserOutlined, setting: SettingOutlined,
}

const Inner = defineComponent({
  setup() {
    return () => h('button', {
      class: 'flex items-center gap-10px w-full text-left px-12px py-10px rounded-10px text-14px',
      style: props.active
        ? { background: 'color-mix(in srgb, var(--ar-accent) 14%, transparent)', color: 'var(--ar-accent)', fontWeight: 600 }
        : { color: 'var(--ar-text)' },
      onClick: () => emit('click'),
    }, [h(iconMap[props.icon] ?? CarOutlined), props.label])
  },
})
</script>

<template>
  <Inner />
</template>
