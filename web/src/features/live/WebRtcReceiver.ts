import { api } from '@/api/client'
import { DIRECTIONS, type Direction, type LiveSession } from '@/api/types'

/** One Unified Plan connection, four recv-only H.264 tracks. No still-image transport. */
export class WebRtcReceiver {
  private pc: RTCPeerConnection
  private seen = new Set<string>()
  private timer = 0
  private closed = false
  private pendingIce: RTCIceCandidateInit[] = []
  constructor(private vehicle: string, private session: LiveSession,
    private track: (direction: Direction, stream: MediaStream) => void,
    private failed: (reason: string) => void) {
    this.pc = new RTCPeerConnection({ iceServers: session.iceServers, bundlePolicy: 'max-bundle' })
    this.pc.onicecandidate = e => {
      if (e.candidate && !this.closed) api.vehicles.sendSignal(this.vehicle, session.sessionId, 'ice', JSON.stringify(e.candidate.toJSON())).catch(() => {})
    }
    this.pc.ontrack = e => {
      const index = this.pc.getTransceivers().indexOf(e.transceiver)
      const direction = DIRECTIONS[index]
      if (direction) this.track(direction, new MediaStream([e.track]))
    }
    this.pc.onconnectionstatechange = () => {
      if (this.pc.connectionState === 'failed' || this.pc.connectionState === 'disconnected') this.failed('视频连接已中断，请重新连接')
    }
  }
  async start() {
    const h264 = RTCRtpReceiver.getCapabilities('video')?.codecs.filter(c => c.mimeType.toLowerCase() === 'video/h264') ?? []
    if (!h264.length) throw new Error('当前浏览器不支持 H.264 实时视频')
    for (const _ of DIRECTIONS) {
      const transceiver = this.pc.addTransceiver('video', { direction: 'recvonly' })
      transceiver.setCodecPreferences(h264)
    }
    const offer = await this.pc.createOffer()
    if (this.closed) return
    await this.pc.setLocalDescription(offer)
    await api.vehicles.sendSignal(this.vehicle, this.session.sessionId, 'offer', JSON.stringify(offer))
    this.poll()
  }
  private async poll() {
    if (this.closed) return
    try {
      const result = await api.vehicles.signals(this.vehicle, this.session.sessionId)
      for (const signal of result.signals) {
        if (this.closed || this.seen.has(signal.id)) continue
        this.seen.add(signal.id)
        const data = JSON.parse(signal.data)
        if (signal.type === 'answer') {
          await this.pc.setRemoteDescription(data)
          for (const candidate of this.pendingIce) await this.pc.addIceCandidate(candidate)
          this.pendingIce = []
        } else if (signal.type === 'ice') {
          if (this.pc.remoteDescription) await this.pc.addIceCandidate(data)
          else this.pendingIce.push(data)
        }
      }
    } catch (e: any) {
      if (e.status === 401 || e.status === 403 || e.status === 404) { this.failed('观看会话或车辆授权已结束'); this.close(); return }
    }
    if (!this.closed) this.timer = window.setTimeout(() => this.poll(), 1000)
  }
  close() {
    if (this.closed) return
    this.closed = true; clearTimeout(this.timer)
    this.pc.getReceivers().forEach(r => r.track?.stop()); this.pc.close()
  }
}
