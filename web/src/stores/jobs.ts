import { defineStore } from 'pinia'
import { api } from '@/api/client'
import type { Job } from '@/api/types'

// 作业跟踪：轮询 GET /jobs/{id}，完成后回调
export const useJobsStore = defineStore('jobs', {
  state: () => ({
    watching: new Map<string, { job?: Job; onDone?: (job: Job) => void; onUpdate?: (job: Job) => void; timer?: number }>(),
  }),
  actions: {
    watch(jobId: string, opts: { onDone?: (job: Job) => void; onUpdate?: (job: Job) => void } = {}) {
      this.watching.set(jobId, { ...opts })
      this.poll(jobId)
    },
    async poll(jobId: string) {
      const w = this.watching.get(jobId)
      if (!w) return
      try {
        const job = await api.jobs.get(jobId)
        w.job = job
        w.onUpdate?.(job)
        const finished = ['success', 'partial_success', 'failed', 'expired', 'cancelled'].includes(job.state)
        if (finished) {
          w.onDone?.(job)
          this.watching.delete(jobId)
        } else {
          w.timer = window.setTimeout(() => this.poll(jobId), 900)
        }
      } catch {
        w.timer = window.setTimeout(() => this.poll(jobId), 2000)
      }
    },
    stop(jobId: string) {
      const w = this.watching.get(jobId)
      if (w?.timer) clearTimeout(w.timer)
      this.watching.delete(jobId)
    },
  },
})
