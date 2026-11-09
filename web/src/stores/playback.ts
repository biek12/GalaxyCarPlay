import { defineStore } from 'pinia'
import { api } from '@/api/client'
import type { TelemetryResult } from '@/api/types'

// 回放数据缓存：只缓存当前浏览片段的旁路数据，切车即清空
export const usePlaybackStore = defineStore('playback', {
  state: () => ({
    cache: new Map<string, TelemetryResult>(),
    loading: false,
    speed: 1,
  }),
  actions: {
    async loadTelemetry(vid: string, cid: string): Promise<TelemetryResult> {
      const key = `${vid}/${cid}`
      const hit = this.cache.get(key)
      if (hit) return hit
      this.loading = true
      try {
        const t = await api.clips.telemetry(vid, cid)
        this.cache.set(key, t)
        if (this.cache.size > 6) {
          // 有界缓存
          const first = this.cache.keys().next().value
          if (first) this.cache.delete(first)
        }
        return t
      } finally {
        this.loading = false
      }
    },
    clear() {
      this.cache.clear()
    },
  },
})
