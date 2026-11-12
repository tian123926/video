/**
 * 短视频切换时的「后续视频首段」预取：
 * - 一次最多并发 8 个视频；
 * - 每个视频只取 init + 第 1 个分片就停止；
 * - 切换视频时中断上一轮请求，不再请求已经滑过的内容。
 */
import { parseMpd } from './dash'

/** 单轮最多预取的视频数量 / 并发上限 */
const MAX_VIDEOS = 8
/** 缓存上限：8 个视频 ×（init + 首段），足够切换使用 */
const CACHE_LIMIT = 40

const cache = new Map<string, ArrayBuffer>()
const completed = new Set<string>()
let controller: AbortController | null = null

function cacheKey(videoId: string, track: string, index: number): string {
  return `${videoId}:${track}:${index}`
}

function remember(key: string, data: ArrayBuffer): void {
  cache.set(key, data)
  while (cache.size > CACHE_LIMIT) {
    const oldest = cache.keys().next().value
    if (oldest === undefined) break
    cache.delete(oldest)
  }
}

export function takeCachedSegment(
  videoId: string,
  track: string,
  index: number
): ArrayBuffer | undefined {
  return cache.get(cacheKey(videoId, track, index))
}

export function putCachedSegment(
  videoId: string,
  track: string,
  index: number,
  data: ArrayBuffer
): void {
  remember(cacheKey(videoId, track, index), data)
}

export function cancelPrefetch(): void {
  controller?.abort()
  controller = null
}

async function prefetchOne(videoId: string, mpdUrl: string, signal: AbortSignal): Promise<void> {
  const response = await fetch(mpdUrl, { signal })
  if (!response.ok) throw new Error('索引加载失败')
  const manifest = parseMpd(await response.text(), mpdUrl)
  if (signal.aborted) return

  const init = await fetch(manifest.video.initUrl, { signal })
  remember(cacheKey(videoId, 'video', -1), await init.arrayBuffer())
  if (signal.aborted) return

  const first = manifest.video.segmentUrls[0]
  if (!first) return
  const segment = await fetch(first, { signal })
  remember(cacheKey(videoId, 'video', 0), await segment.arrayBuffer())
}

/**
 * 预取后续视频的首个分片。传入顺序即优先级，已完成过的视频会被跳过。
 */
export function prefetchUpcoming(items: Array<{ id: string; mpdUrl: string }>): void {
  cancelPrefetch()
  const pending = items.filter((item) => item.mpdUrl && !completed.has(item.id)).slice(0, MAX_VIDEOS)
  if (!pending.length) return

  const current = new AbortController()
  controller = current
  let cursor = 0

  const worker = async (): Promise<void> => {
    while (cursor < pending.length) {
      const item = pending[cursor]
      cursor += 1
      if (current.signal.aborted) return
      try {
        await prefetchOne(item.id, item.mpdUrl, current.signal)
        completed.add(item.id)
      } catch {
        // 单个视频预取失败不影响其它任务
      }
    }
  }

  const workers = Array.from({ length: Math.min(MAX_VIDEOS, pending.length) }, () => worker())
  void Promise.all(workers)
}
