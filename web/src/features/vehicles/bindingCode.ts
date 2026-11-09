/** QR only contains an opaque one-use code. Accept our own link, never navigate to scanned URLs. */
export function parseBindingCode(raw: string): string {
  const value = raw.trim()
  if (/^[A-Za-z0-9_-]{16}$/.test(value)) return value
  const url = new URL(value)
  if (url.origin !== window.location.origin || !url.pathname.endsWith('/vehicles')) throw new Error('这不是本站的车辆绑定二维码')
  const code = new URLSearchParams(url.hash.slice(1)).get('bind') ?? ''
  if (!/^[A-Za-z0-9_-]{16}$/.test(code)) throw new Error('车辆绑定码格式不正确')
  return code
}
