export const STORAGE_TOKEN = 'vod_token'
export const STORAGE_USERNAME = 'vod_username'

const DEFAULT_API_BASE = 'http://127.0.0.1:30080/api/video'

export const API_BASE = (import.meta.env.VITE_VOD_API_BASE || DEFAULT_API_BASE).replace(/\/$/, '')

export class ApiError extends Error {
  readonly code: number
  readonly status: number

  constructor(message: string, code = 0, status = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

interface Envelope<T> {
  code: number
  message: string
  data: T
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const token = localStorage.getItem(STORAGE_TOKEN)
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  } catch {
    throw new ApiError('无法连接后端服务，请确认主服务已启动', 0, 0)
  }

  const text = await response.text()
  let payload: Envelope<T> | null = null
  try {
    payload = text ? (JSON.parse(text) as Envelope<T>) : null
  } catch {
    payload = null
  }

  if (!response.ok) {
    if (response.status === 401) localStorage.removeItem(STORAGE_TOKEN)
    throw new ApiError(payload?.message || `请求失败（HTTP ${response.status}）`, payload?.code ?? 0, response.status)
  }
  if (!payload || payload.code !== 0) {
    throw new ApiError(payload?.message || '响应格式异常', payload?.code ?? -1, response.status)
  }
  return payload.data
}
