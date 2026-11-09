// RFC 6238 TOTP（演示 mock 与真实后端语义一致；生产校验在服务端）
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

export function base32Encode(buf: Uint8Array): string {
  let bits = 0
  let value = 0
  let out = ''
  for (const byte of buf) {
    value = (value << 8) | byte
    bits += 8
    while (bits >= 5) {
      out += B32[(value >>> (bits - 5)) & 31]
      bits -= 5
    }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31]
  return out
}

export function base32Decode(s: string): Uint8Array {
  const clean = s.toUpperCase().replace(/[^A-Z2-7]/g, '')
  let bits = 0
  let value = 0
  const out: number[] = []
  for (const ch of clean) {
    value = (value << 5) | B32.indexOf(ch)
    bits += 5
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 255)
      bits -= 8
    }
  }
  return new Uint8Array(out)
}

async function hmacSha1(key: Uint8Array, msg: Uint8Array): Promise<Uint8Array> {
  const ck = await crypto.subtle.importKey('raw', key as unknown as ArrayBuffer, { name: 'HMAC', hash: 'SHA-1' }, false, ['sign'])
  const sig = await crypto.subtle.sign('HMAC', ck, msg as unknown as ArrayBuffer)
  return new Uint8Array(sig)
}

export function randomSecret(): string {
  const buf = crypto.getRandomValues(new Uint8Array(20))
  return base32Encode(buf)
}

export async function totpAt(secret: string, timeMs: number, step = 30): Promise<string> {
  const counter = Math.floor(timeMs / 1000 / step)
  const msg = new Uint8Array(8)
  let c = counter
  for (let i = 7; i >= 0; i--) {
    msg[i] = c & 255
    c = Math.floor(c / 256)
  }
  const mac = await hmacSha1(base32Decode(secret), msg)
  const off = mac[mac.length - 1] & 0x0f
  const bin = ((mac[off] & 0x7f) << 24) | (mac[off + 1] << 16) | (mac[off + 2] << 8) | mac[off + 3]
  return String(bin % 1_000_000).padStart(6, '0')
}

export function timeStepAt(timeMs: number, step = 30): number {
  return Math.floor(timeMs / 1000 / step)
}
