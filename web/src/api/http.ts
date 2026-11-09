// 统一请求层：错误映射 {code,message,requestId,retryAfterMs?}、401 处理、幂等键。
// 真实后端接入时走 fetch；mock 模式由 mock/server 提供实现。

export class ApiError extends Error {
  status: number
  code: string
  requestId: string
  retryAfterMs?: number

  constructor(status: number, code: string, message: string, opts: { requestId?: string; retryAfterMs?: number } = {}) {
    super(message)
    this.status = status
    this.code = code
    this.requestId = opts.requestId ?? 'mock-req-' + Math.random().toString(36).slice(2, 8)
    this.retryAfterMs = opts.retryAfterMs
  }
}

export interface RawResponse<T = unknown> {
  status: number
  data: T
}

let unauthorizedHandler: (() => void) | null = null
export function setUnauthorizedHandler(fn: () => void) {
  unauthorizedHandler = fn
}

// 每个修改请求自动附带 Idempotency-Key（契约要求）
const idemKeys = new Map<string, string>()
export function idempotencyKey(action: string): string {
  if (!idemKeys.has(action)) idemKeys.set(action, crypto.randomUUID())
  return idemKeys.get(action)!
}
export function resetIdempotency(action: string) {
  idemKeys.delete(action)
}

export const http = {
  onUnauthorized(fn: () => void) {
    setUnauthorizedHandler(fn)
  },
}
