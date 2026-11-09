import type { PresenceState } from '@/api/types'

export const PRESENCE_COLOR: Record<PresenceState, string> = {
  online: '#00b96b',
  sleeping: '#e6a700',
  waking: '#2f7dd1',
  offline: '#9aa3ad',
  unknown: '#9aa3ad',
  fault: '#e5484d',
}

export const PRESENCE_LABEL: Record<PresenceState, string> = {
  online: '在线',
  sleeping: '休眠',
  waking: '唤醒中',
  offline: '离线',
  unknown: '未知',
  fault: '故障',
}
