import SparkMD5 from 'spark-md5'
import { ApiError, request } from './http'

export interface PartUrlItem {
  partNumber: number
  url: string
  expiresAt: string
}

export interface UploadInitData {
  uploadId: string | null
  videoId: number
  bucket: string
  objectName: string
  chunkSize: number
  totalChunks: number
  expireAt: string | null
  existed: boolean
  partUrls: PartUrlItem[]
}

interface UploadedPart {
  partNumber: number
  etag: string
  size: number
}

interface ListPartsData {
  uploadId: string
  totalChunks: number
  uploadedParts: UploadedPart[]
  missingParts: number[]
}

interface CompleteData {
  videoId: number
  status: number
  transcodeTaskId: number | null
  mergedObject: string
}

interface UploadStatusData {
  videoId: number
  uploadStatus: number | null
  videoStatus: number
  progress: number | null
  playUrl: string | null
  errorMsg: string | null
}

export interface UploadOptions {
  onProgress?: (ratio: number) => void
  onStatus?: (message: string) => void
  onSession?: (uploadId: string) => void
  signal?: AbortSignal
  pollIntervalMs?: number
  pollTimeoutMs?: number
}

interface CachedSession {
  uploadId: string
  videoId: number
  bucket: string
  objectName: string
  chunkSize: number
  totalChunks: number
  expireAt: string | null
}

const SESSION_PREFIX = 'vod_upload_session_'
const ALLOWED_EXTENSIONS = ['mp4', 'mov', 'm4v', 'webm']
const MAX_FILE_SIZE = 2 * 1024 * 1024 * 1024
const MAX_CONCURRENCY = 3
const HASH_CHUNK_SIZE = 2 * 1024 * 1024

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

function extensionOf(name: string): string {
  const index = name.lastIndexOf('.')
  return index > -1 ? name.slice(index + 1).toLowerCase() : ''
}

export function validateVideoFile(file: File): string | null {
  if (!ALLOWED_EXTENSIONS.includes(extensionOf(file.name))) {
    return '仅支持 mp4 / mov / m4v / webm 格式'
  }
  if (file.size <= 0) return '文件为空'
  if (file.size > MAX_FILE_SIZE) return '文件超过 2GB'
  return null
}

async function computeMd5(file: File, onProgress?: (ratio: number) => void): Promise<string> {
  const spark = new SparkMD5.ArrayBuffer()
  let offset = 0
  while (offset < file.size) {
    const end = Math.min(file.size, offset + HASH_CHUNK_SIZE)
    const buffer = await file.slice(offset, end).arrayBuffer()
    spark.append(buffer)
    offset = end
    onProgress?.(offset / file.size)
  }
  return spark.end()
}

function readSession(hash: string): CachedSession | null {
  const raw = localStorage.getItem(`${SESSION_PREFIX}${hash}`)
  if (!raw) return null
  try {
    return JSON.parse(raw) as CachedSession
  } catch {
    return null
  }
}

function saveSession(hash: string, data: UploadInitData): void {
  if (!data.uploadId) return
  const cached: CachedSession = {
    uploadId: data.uploadId,
    videoId: data.videoId,
    bucket: data.bucket,
    objectName: data.objectName,
    chunkSize: data.chunkSize,
    totalChunks: data.totalChunks,
    expireAt: data.expireAt
  }
  localStorage.setItem(`${SESSION_PREFIX}${hash}`, JSON.stringify(cached))
}

function clearSession(hash: string): void {
  localStorage.removeItem(`${SESSION_PREFIX}${hash}`)
}

function initUpload(file: File, fileHash: string): Promise<UploadInitData> {
  return request<UploadInitData>('/upload/init', {
    method: 'POST',
    body: JSON.stringify({
      fileName: file.name,
      fileSize: file.size,
      fileHash,
      contentType: file.type || 'application/octet-stream'
    })
  })
}

function requestPartUrls(uploadId: string, partNumbers: number[]): Promise<{ parts: PartUrlItem[] }> {
  return request<{ parts: PartUrlItem[] }>('/upload/part-url', {
    method: 'POST',
    body: JSON.stringify({ uploadId, partNumbers })
  })
}

function listParts(uploadId: string): Promise<ListPartsData> {
  return request<ListPartsData>(`/upload/list-parts?uploadId=${encodeURIComponent(uploadId)}`)
}

function completeUpload(uploadId: string, parts: Array<{ partNumber: number; etag: string }>): Promise<CompleteData> {
  return request<CompleteData>('/upload/complete', {
    method: 'POST',
    body: JSON.stringify({ uploadId, parts })
  })
}

export async function abortUpload(uploadId: string): Promise<void> {
  await request<{ aborted: boolean }>('/upload/abort', {
    method: 'POST',
    body: JSON.stringify({ uploadId })
  })
}

async function fetchStatus(videoId: number): Promise<UploadStatusData> {
  return request<UploadStatusData>(`/upload/status?videoId=${videoId}`)
}

async function putPart(url: string, blob: Blob, signal?: AbortSignal): Promise<void> {
  const response = await fetch(url, {
    method: 'PUT',
    body: blob,
    signal,
    headers: { 'Content-Type': 'application/octet-stream' }
  })
  if (!response.ok) {
    throw new ApiError(`分片上传失败（HTTP ${response.status}）`, 0, response.status)
  }
}

function statusText(videoStatus: number): string {
  switch (videoStatus) {
    case 0:
      return '排队等待转码…'
    case 1:
      return '已上传，等待转码…'
    case 2:
      return '正在转码，请稍候…'
    case 3:
      return '转码完成'
    default:
      return '转码失败'
  }
}

export async function uploadVideo(file: File, options: UploadOptions = {}): Promise<number> {
  const invalid = validateVideoFile(file)
  if (invalid) throw new ApiError(invalid, 40001, 400)

  const { onProgress, onStatus, onSession, signal } = options

  onStatus?.('计算文件指纹…')
  const hash = await computeMd5(file, (ratio) => onStatus?.(`计算文件指纹 ${Math.floor(ratio * 100)}%`))

  let session: UploadInitData | null = null
  const cached = readSession(hash)
  if (cached && (!cached.expireAt || Date.now() < new Date(cached.expireAt).getTime())) {
    try {
      const parts = await listParts(cached.uploadId)
      session = {
        uploadId: cached.uploadId,
        videoId: cached.videoId,
        bucket: cached.bucket,
        objectName: cached.objectName,
        chunkSize: cached.chunkSize,
        totalChunks: parts.totalChunks,
        expireAt: cached.expireAt,
        existed: false,
        partUrls: []
      }
      onStatus?.('发现未完成的上传，继续断点续传')
    } catch {
      session = null
    }
  }

  if (!session) {
    session = await initUpload(file, hash)
    if (session.existed) {
      onProgress?.(1)
      onStatus?.('检测到相同文件，已秒传')
      return session.videoId
    }
    saveSession(hash, session)
  }

  if (!session.uploadId) throw new ApiError('上传会话创建失败', 40903, 409)
  onSession?.(session.uploadId)

  const chunkSize = session.chunkSize
  const totalChunks = session.totalChunks
  const urlMap = new Map<number, string>()
  session.partUrls.forEach((part) => urlMap.set(part.partNumber, part.url))

  let uploadedParts: UploadedPart[] = []
  try {
    const parts = await listParts(session.uploadId)
    uploadedParts = parts.uploadedParts
  } catch {
    uploadedParts = []
  }
  const uploadedNumbers = new Set(uploadedParts.map((part) => part.partNumber))
  const missing: number[] = []
  for (let n = 1; n <= totalChunks; n += 1) {
    if (!uploadedNumbers.has(n)) missing.push(n)
  }

  for (let i = 0; i < missing.length; i += 20) {
    const batch = missing.slice(i, i + 20)
    const result = await requestPartUrls(session.uploadId, batch)
    result.parts.forEach((part) => urlMap.set(part.partNumber, part.url))
  }

  let completed = totalChunks - missing.length
  let cursor = 0
  const worker = async (): Promise<void> => {
    while (cursor < missing.length) {
      if (signal?.aborted) throw new DOMException('上传已取消', 'AbortError')
      const partNumber = missing[cursor]
      cursor += 1
      const url = urlMap.get(partNumber)
      if (!url) throw new ApiError(`分片 ${partNumber} 缺少上传地址`, 50001, 500)
      const start = (partNumber - 1) * chunkSize
      const blob = file.slice(start, Math.min(file.size, start + chunkSize))
      await putPart(url, blob, signal)
      completed += 1
      onProgress?.(Math.min(0.98, completed / totalChunks))
    }
  }
  await Promise.all(
    Array.from({ length: Math.min(MAX_CONCURRENCY, Math.max(1, missing.length)) }, () => worker())
  )

  onStatus?.('校验分片…')
  const merged = await listParts(session.uploadId)
  if (merged.missingParts.length > 0) {
    throw new ApiError(`还有 ${merged.missingParts.length} 个分片未上传`, 40901, 409)
  }
  const parts = merged.uploadedParts
    .sort((a, b) => a.partNumber - b.partNumber)
    .map((part) => ({ partNumber: part.partNumber, etag: part.etag }))

  onProgress?.(1)
  onStatus?.('合并分片…')
  try {
    await completeUpload(session.uploadId, parts)
  } catch (error) {
    if (!(error instanceof ApiError) || error.code !== 50300) throw error
  }
  clearSession(hash)

  onStatus?.(statusText(1))
  const deadline = Date.now() + (options.pollTimeoutMs ?? 5 * 60 * 1000)
  const interval = options.pollIntervalMs ?? 2000
  while (Date.now() < deadline) {
    if (signal?.aborted) throw new DOMException('上传已取消', 'AbortError')
    const status = await fetchStatus(session.videoId)
    if (status.videoStatus === 3) {
      onStatus?.('转码完成')
      return session.videoId
    }
    if (status.videoStatus === 4) {
      throw new ApiError(status.errorMsg || '转码失败', 50003, 500)
    }
    onStatus?.(statusText(status.videoStatus))
    await sleep(interval)
  }
  onStatus?.('转码时间较长，可稍后在首页查看')
  return session.videoId
}
