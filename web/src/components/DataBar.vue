<script setup lang="ts">
import { computed } from 'vue'
import { Tag } from 'ant-design-vue'
import type { TelemetrySample } from '@/api/types'

const props = withDefaults(defineProps<{
  sample: TelemetrySample | null
  intervalMs?: number
  expanded?: boolean
}>(), { intervalMs: 500, expanded: false })

// 数据时效规则：缺失显示「缺失」，超时显示「过期」，不补零（规格 §2.3-6 / §5 P3）
const q = computed(() => props.sample?.q ?? 'missing')
const unknown = (v: unknown) => (v == null ? '—' : v)

const speed = computed(() => (q.value === 'good' ? props.sample?.speed : null))
const gear = computed(() => (q.value === 'good' ? props.sample?.gear : null))
const rpm = computed(() => (q.value === 'good' ? props.sample?.rpm : null))
const steer = computed(() => (q.value === 'good' ? props.sample?.steer : null))
const brake = computed(() => (q.value === 'good' ? Math.round(props.sample?.brake ?? 0) : null))
const throttle = computed(() => (q.value === 'good' ? Math.round(props.sample?.throttle ?? 0) : null))
const turn = computed(() => (q.value === 'good' ? props.sample?.turn : null))
const turnLabel = computed(() => ({ off: '关闭', left: '左转', right: '右转', both: '双闪' } as Record<string, string>)[turn.value ?? 'off'] ?? '—')
const lane = computed(() => (q.value === 'good' ? props.sample?.laneAssist : null))
const cruiseOn = computed(() => (q.value === 'good' ? props.sample?.cruiseOn : null))
const cruiseSet = computed(() => props.sample?.cruiseSet)
const beam = computed(() => (q.value === 'good' ? props.sample?.beam : null))
const beamLabel = computed(() => ({ off: '关闭', low: '近光', high: '远光' } as Record<string, string>)[beam.value ?? 'off'] ?? '—')
const fog = computed(() => (q.value === 'good' ? props.sample?.fog : null))
const autoHold = computed(() => (q.value === 'good' ? props.sample?.autoHold : null))
const park = computed(() => (q.value === 'good' ? props.sample?.parkBrake : null))
const fuel = computed(() => (q.value === 'good' ? props.sample?.fuelPct : null))
const evRange = computed(() => (q.value === 'good' ? props.sample?.evRangeKm : null))
const odo = computed(() => (q.value === 'good' ? props.sample?.odoKm : null))
const tIn = computed(() => (q.value === 'good' ? props.sample?.tempInC : null))
const tOut = computed(() => (q.value === 'good' ? props.sample?.tempOutC : null))

function pedalColor(v: number | null | undefined): string {
  if (v == null) return 'var(--ar-line)'
  if (v >= 60) return 'var(--ar-danger)'
  if (v >= 25) return 'var(--ar-warn)'
  return 'var(--ar-accent)'
}
const yesNo = (v: boolean | null | undefined) => (v == null ? '—' : v ? '是' : '否')
</script>

<template>
  <div class="ar-panel px-12px py-8px select-none">
    <div class="flex items-center justify-between gap-8px">
      <div class="flex items-center gap-16px flex-wrap text-13px">
        <span>车速 <b class="tabular-nums">{{ unknown(speed) }}</b> <span class="ar-text-2">km/h</span></span>
        <span>挡位 <b>{{ unknown(gear) }}</b></span>
        <span>转向灯 <b>{{ turnLabel }}</b></span>
        <span>巡航 <b>{{ cruiseOn ? (cruiseSet != null ? `${cruiseSet} km/h` : '开启') : '关闭' }}</b></span>
        <Tag v-if="q === 'stale'" color="orange">数据过期</Tag>
        <Tag v-else-if="q === 'missing'" color="red">数据缺失</Tag>
      </div>
      <span class="ar-text-2 text-12px hide-mobile">采样 {{ intervalMs }}ms · 展开显示全量</span>
    </div>

    <div v-if="expanded" class="mt-8px pt-8px flex flex-col gap-8px" style="border-top: 1px dashed var(--ar-line)">
      <!-- 油门 / 刹车力度条：颜色区分力度 -->
      <div class="flex items-center gap-10px">
        <span class="w-40px text-12px ar-text-2">刹车</span>
        <div class="pedal-bar flex-1"><i :style="{ width: `${brake ?? 0}%`, background: pedalColor(brake) }" /></div>
        <span class="w-46px text-right text-13px tabular-nums">{{ brake == null ? '—' : `${brake}%` }}</span>
      </div>
      <div class="flex items-center gap-10px">
        <span class="w-40px text-12px ar-text-2">油门</span>
        <div class="pedal-bar flex-1"><i :style="{ width: `${throttle ?? 0}%`, background: pedalColor(throttle) }" /></div>
        <span class="w-46px text-right text-13px tabular-nums">{{ throttle == null ? '—' : `${throttle}%` }}</span>
      </div>
      <div class="grid grid-cols-2 md:grid-cols-5 gap-x-16px gap-y-4px text-13px">
        <span>转速 <b>{{ unknown(rpm) }}</b> <span class="ar-text-2">rpm</span></span>
        <span>转向角 <b>{{ steer == null ? '—' : `${steer > 0 ? '+' : ''}${steer}°` }}</b></span>
        <span>横向辅助 <b>{{ lane == null ? '—' : lane ? '启用' : '退出' }}</b></span>
        <span>灯光 <b>{{ beamLabel }}</b>{{ fog ? ' · 后雾' : '' }}</span>
        <span>Auto Hold <b>{{ yesNo(autoHold) }}</b></span>
        <span>驻车 <b>{{ yesNo(park) }}</b></span>
        <span>油量/电量 <b>{{ fuel == null ? '—' : `${Math.round(fuel)}%` }}</b></span>
        <span>续航 <b>{{ unknown(evRange) }}</b> <span class="ar-text-2">km</span></span>
        <span>总里程 <b>{{ odo == null ? '—' : Math.round(odo) }}</b> <span class="ar-text-2">km</span></span>
        <span>温度 <b>{{ unknown(tIn) }}/{{ unknown(tOut) }}</b> <span class="ar-text-2">°C</span></span>
      </div>
    </div>
  </div>
</template>
