import { defineStore } from 'pinia'
import { api } from '@/api/client'
import type { Vehicle } from '@/api/types'

// 只缓存有界状态；切车时清理上车数据（见交接文档 §7）
export const useVehiclesStore = defineStore('vehicles', {
  state: () => ({
    list: [] as Vehicle[],
    loaded: false,
    loading: false,
    detailCache: {} as Record<string, Vehicle>,
    activeId: (localStorage.getItem('ar_active_vehicle') as string) || '',
  }),
  getters: {
    active(state): Vehicle | null {
      return state.list.find((v) => v.vehicleId === state.activeId) ?? state.list[0] ?? null
    },
    byId(state): (id: string) => Vehicle | null {
      return (id) => state.detailCache[id] ?? state.list.find((v) => v.vehicleId === id) ?? null
    },
  },
  actions: {
    setActive(id: string) {
      this.activeId = id
      localStorage.setItem('ar_active_vehicle', id)
    },
    async loadList(force = false) {
      if (this.loading) return
      if (this.loaded && !force) return
      this.loading = true
      try {
        this.list = await api.vehicles.list()
        if (!this.activeId || !this.list.some((v) => v.vehicleId === this.activeId)) {
          this.setActive(this.list[0]?.vehicleId ?? '')
        }
        this.loaded = true
      } finally {
        this.loading = false
      }
    },
    async refreshDetail(id: string) {
      const v = await api.vehicles.get(id)
      this.detailCache[id] = v
      const i = this.list.findIndex((x) => x.vehicleId === id)
      if (i >= 0) this.list[i] = v
      return v
    },
    clearOnSwitch() {
      // 切车清理（播放器/请求由各 store 自己实现 onVehicleSwitch）
    },
  },
})
