<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Player from 'xgplayer'
import HlsPlugin from 'xgplayer-hls'
import 'xgplayer/es/lang/zh-cn'
import { parseMpd, type DashManifest, type DashTrack } from '@/lib/dash'
import { takeCachedSegment } from '@/lib/prefetch'

type PlayerInstance = InstanceType<typeof Player>
type Mode = 'dash' | 'plugin'

const props = withDefaults(
  defineProps<{
    src: string
    poster: string
    autoplay?: boolean
    loop?: boolean
    controls?: boolean
    muted?: boolean
  }>(),
  {
    autoplay: false,
    loop: false,
    controls: true,
    muted: false
  }
)

const emit = defineEmits<{
  ready: [HTMLVideoElement | PlayerInstance]
  progress: [number, number]
  playing: [boolean]
}>()

/** 分片并发上限：一次最多 8 个请求，够一段就停 */
const MAX_PARALLEL_FETCH = 8
/** 当前播放位置之后预取的分片数量 */
const AHEAD_SEGMENTS = 6

interface TrackRuntime {
  track: DashTrack
  buffer: SourceBuffer
  nextIndex: number
  inflight: Set<number>
  queue: Array<{ index: number; data: ArrayBuffer }>
  appending: boolean
  initAppended: boolean
}

const detectProtocol = (url: string): 'hls' | 'dash' | 'native' => {
  const clean = url.split('?')[0].split('#')[0].toLowerCase()
  if (clean.endsWith('.mpd')) return 'dash'
  if (clean.endsWith('.m3u8')) return 'hls'
  return 'native'
}

const mode = ref<Mode>(detectProtocol(props.src) === 'dash' ? 'dash' : 'plugin')
const videoRef = ref<HTMLVideoElement>()
const pluginHost = ref<HTMLElement>()
/** 播放链路状态，便于排查（展示在容器 data 属性上） */
const dashState = ref('idle')

let player: PlayerInstance | null = null
let playing = false

// ---------- DASH / MSE ----------
let mediaSource: MediaSource | null = null
let mediaObjectUrl = ''
let manifest: DashManifest | null = null
let runtimes: TrackRuntime[] = []
let activeFetches = 0
let disposed = false

/** 从索引地址中取出视频 ID，用于复用短视频预取缓存 */
const cachedVideoId = (): string => {
  const matched = /\/api\/video\/(\d+)\//.exec(props.src)
  return matched ? matched[1] : ''
}

const loadSegment = (runtime: TrackRuntime, index: number): Promise<ArrayBuffer> => {
  const videoId = cachedVideoId()
  if (videoId) {
    const cached = takeCachedSegment(videoId, runtime.track.contentType, index)
    if (cached) return Promise.resolve(cached)
  }
  return fetch(runtime.track.segmentUrls[index]).then((response) => {
    if (!response.ok) throw new Error(`分片 ${index} 下载失败`)
    return response.arrayBuffer()
  })
}

const loadInit = (track: DashTrack): Promise<ArrayBuffer> => {
  const videoId = cachedVideoId()
  if (videoId) {
    const cached = takeCachedSegment(videoId, track.contentType, -1)
    if (cached) return Promise.resolve(cached)
  }
  return fetch(track.initUrl).then((response) => {
    if (!response.ok) throw new Error('初始化分片下载失败')
    return response.arrayBuffer()
  })
}

const targetIndex = (runtime: TrackRuntime): number => {
  const element = videoRef.value
  const total = runtime.track.segmentUrls.length - 1
  if (!element) return Math.min(total, AHEAD_SEGMENTS)
  const current = Number.isFinite(element.currentTime) ? element.currentTime : 0
  const base = Math.floor(current / runtime.track.segmentDuration)
  return Math.max(0, Math.min(total, base + AHEAD_SEGMENTS))
}

const appendNext = (runtime: TrackRuntime): void => {
  if (runtime.appending || !runtime.initAppended || !runtime.queue.length) return
  const item = runtime.queue.shift()
  if (!item) return
  runtime.appending = true
  try {
    runtime.buffer.appendBuffer(item.data)
  } catch {
    // 缓冲区异常时丢弃该分片，保持播放继续
    runtime.appending = false
    appendNext(runtime)
  }
}

const pump = (): void => {
  if (disposed || !manifest) return
  for (const runtime of runtimes) {
    const limit = targetIndex(runtime)
    while (
      activeFetches < MAX_PARALLEL_FETCH &&
      runtime.nextIndex <= limit &&
      runtime.nextIndex < runtime.track.segmentUrls.length
    ) {
      const index = runtime.nextIndex
      runtime.nextIndex += 1
      runtime.inflight.add(index)
      activeFetches += 1
      void loadSegment(runtime, index)
        .then((data) => {
          activeFetches -= 1
          runtime.inflight.delete(index)
          runtime.queue.push({ index, data })
          runtime.queue.sort((left, right) => left.index - right.index)
          appendNext(runtime)
          pump()
        })
        .catch((error) => {
          activeFetches -= 1
          runtime.inflight.delete(index)
          // 跳过失败分片，继续请求后续内容
          console.warn('[dash] 分片下载失败', runtime.track.contentType, index, error)
          pump()
        })
    }
  }
}

const setupSourceBuffers = (): void => {
  if (!mediaSource || !manifest || disposed) return
  try {
    mediaSource.duration = manifest.duration || 0
  } catch {
    // 部分浏览器不允许手动设置时长
  }

  const tracks: DashTrack[] = [manifest.video]
  if (manifest.audio) tracks.push(manifest.audio)
  runtimes = []

  for (const track of tracks) {
    const mime = `${track.mimeType}; codecs="${track.codecs}"`
    if (!MediaSource.isTypeSupported(mime)) continue
    let buffer: SourceBuffer
    try {
      buffer = mediaSource.addSourceBuffer(mime)
    } catch {
      continue
    }
    buffer.mode = 'segments'
    const runtime: TrackRuntime = {
      track,
      buffer,
      nextIndex: 0,
      inflight: new Set(),
      queue: [],
      appending: true,
      initAppended: false
    }
    buffer.addEventListener('updateend', () => {
      runtime.appending = false
      if (!runtime.initAppended) runtime.initAppended = true
      appendNext(runtime)
      pump()
    })
    runtimes.push(runtime)

    void loadInit(track)
      .then((data) => {
        if (disposed) return
        try {
          buffer.appendBuffer(data)
        } catch {
          runtime.appending = false
          runtime.initAppended = true
          pump()
        }
      })
      .catch(() => {
        runtime.appending = false
        runtime.initAppended = true
        pump()
      })
  }

  if (!runtimes.length) {
    dashState.value = 'error:浏览器不支持该编码'
    emit('playing', false)
    return
  }
  dashState.value = `buffers:${runtimes.length}`
  pump()
}

const createDash = async (): Promise<void> => {
  const element = videoRef.value
  if (!element) return
  element.poster = props.poster
  element.controls = props.controls
  element.loop = false
  element.muted = props.muted
  element.playsInline = true
  try {
    const response = await fetch(props.src)
    if (!response.ok) throw new Error(`索引加载失败 HTTP ${response.status}`)
    const xml = await response.text()
    if (disposed) return
    manifest = parseMpd(xml, props.src)
  } catch (error) {
    dashState.value = `error:${error instanceof Error ? error.message : '未知错误'}`
    console.error('[dash] 索引解析失败', error)
    emit('playing', false)
    return
  }
  dashState.value = `manifest:${manifest.video.segmentUrls.length}`
  mediaSource = new MediaSource()
  mediaObjectUrl = URL.createObjectURL(mediaSource)
  // 先注册监听再设置 src，避免 sourceopen 早于监听
  mediaSource.addEventListener('sourceopen', setupSourceBuffers, { once: true })
  element.src = mediaObjectUrl
  if (mediaSource.readyState === 'open') {
    setupSourceBuffers()
  }
  emit('ready', element)
}

const destroyDash = (): void => {
  runtimes = []
  manifest = null
  activeFetches = 0
  if (mediaSource) {
    mediaSource.removeEventListener('sourceopen', setupSourceBuffers)
    if (mediaSource.readyState === 'open') {
      try {
        mediaSource.endOfStream()
      } catch {
        // ignore
      }
    }
    mediaSource = null
  }
  if (mediaObjectUrl) {
    URL.revokeObjectURL(mediaObjectUrl)
    mediaObjectUrl = ''
  }
}

const onTimeUpdate = (): void => {
  const element = videoRef.value
  if (!element) return
  const total = element.duration && Number.isFinite(element.duration) ? element.duration : 0
  emit('progress', element.currentTime, total || manifest?.duration || 0)
  pump()
}

const onSavedPlay = (): void => {
  playing = true
  emit('playing', true)
}

const onSavedPause = (): void => {
  playing = false
  emit('playing', false)
}

const onSavedEnded = (): void => {
  playing = false
  emit('playing', false)
  if (props.loop && videoRef.value) {
    videoRef.value.currentTime = 0
    void videoRef.value.play().catch(() => undefined)
  }
}

const bindDashEvents = (element: HTMLVideoElement): void => {
  element.addEventListener('timeupdate', onTimeUpdate)
  element.addEventListener('durationchange', onTimeUpdate)
  element.addEventListener('play', onSavedPlay)
  element.addEventListener('pause', onSavedPause)
  element.addEventListener('ended', onSavedEnded)
  element.addEventListener('canplay', onDashCanPlay)
}

const unbindDashEvents = (element: HTMLVideoElement): void => {
  element.removeEventListener('timeupdate', onTimeUpdate)
  element.removeEventListener('durationchange', onTimeUpdate)
  element.removeEventListener('play', onSavedPlay)
  element.removeEventListener('pause', onSavedPause)
  element.removeEventListener('ended', onSavedEnded)
  element.removeEventListener('canplay', onDashCanPlay)
}

const onDashCanPlay = (): void => {
  if (props.autoplay && videoRef.value && videoRef.value.paused) {
    void videoRef.value.play().catch(() => undefined)
  }
}

// ---------- HLS / 原生（继续使用 xgplayer）----------
const createPlugin = (): void => {
  if (!pluginHost.value || player) return
  const protocol = detectProtocol(props.src)
  const plugins = protocol === 'hls' ? [HlsPlugin] : []
  const options: Record<string, unknown> = {
    el: pluginHost.value,
    url: props.src,
    poster: props.poster,
    width: '100%',
    height: '100%',
    lang: 'zh-cn',
    autoplay: props.autoplay,
    loop: props.loop,
    muted: props.muted,
    volume: 0.55,
    controls: props.controls,
    playsinline: true,
    plugins,
    preloadTime: 45,
    minCachedTime: 10,
    videoAttributes: { preload: 'auto', playsinline: 'true' }
  }
  player = new Player(options as never)
  player.on('timeupdate', () => {
    emit('progress', player?.currentTime ?? 0, player?.duration ?? 0)
  })
  player.on('durationchange', () => {
    emit('progress', player?.currentTime ?? 0, player?.duration ?? 0)
  })
  player.on('play', () => {
    playing = true
    emit('playing', true)
  })
  player.on('pause', () => {
    playing = false
    emit('playing', false)
  })
  player.on('ended', () => {
    playing = false
    emit('playing', false)
  })
  emit('ready', player)
}

const destroyPlugin = (): void => {
  player?.destroy()
  player = null
  playing = false
}

// ---------- 生命周期 ----------
const create = async (): Promise<void> => {
  disposed = false
  mode.value = detectProtocol(props.src) === 'dash' ? 'dash' : 'plugin'
  await nextTick()
  if (mode.value === 'dash') {
    if (videoRef.value) bindDashEvents(videoRef.value)
    await createDash()
  } else {
    createPlugin()
  }
}

const destroy = (): void => {
  disposed = true
  if (videoRef.value) {
    unbindDashEvents(videoRef.value)
    videoRef.value.removeAttribute('src')
    videoRef.value.load()
  }
  destroyDash()
  destroyPlugin()
}

onMounted(() => {
  void create()
})

onBeforeUnmount(destroy)

watch(
  () => props.src,
  () => {
    destroy()
    void create()
  }
)

// ---------- 暴露的播放控制 ----------
const play = (): void => {
  if (mode.value === 'dash') void videoRef.value?.play().catch(() => undefined)
  else player?.play()
}

const pause = (): void => {
  if (mode.value === 'dash') videoRef.value?.pause()
  else player?.pause()
}

const toggle = (): void => {
  if (mode.value === 'dash') {
    const element = videoRef.value
    if (!element) return
    if (element.paused) void element.play().catch(() => undefined)
    else element.pause()
    return
  }
  if (!player) return
  if (playing) player.pause()
  else player.play()
}

const seekTo = (time: number): void => {
  if (mode.value === 'dash') {
    const element = videoRef.value
    if (!element) return
    element.currentTime = Math.max(0, time)
    pump()
    return
  }
  player?.seek(time)
}

const fullscreen = (): void => {
  if (mode.value === 'dash') {
    const element = videoRef.value
    if (!element) return
    if (document.fullscreenElement) void document.exitFullscreen()
    else void element.requestFullscreen().catch(() => undefined)
    return
  }
  if (!player) return
  if (player.fullscreen) player.exitFullscreen()
  else player.getFullscreen().catch(() => {})
}

defineExpose({ play, pause, toggle, seekTo, fullscreen })
</script>

<template>
  <div class="xg-host" :data-dash-state="dashState">
    <video
      v-if="mode === 'dash'"
      ref="videoRef"
      class="xg-video"
      playsinline
      preload="auto"
    ></video>
    <div v-else ref="pluginHost" class="xg-plugin"></div>
  </div>
</template>

<style scoped>
.xg-host,
.xg-video,
.xg-plugin {
  width: 100%;
  height: 100%;
}

.xg-video {
  display: block;
  object-fit: contain;
  background: #000;
}
</style>
