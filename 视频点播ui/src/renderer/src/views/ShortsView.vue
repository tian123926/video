<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import CommentPanel from '@/components/CommentPanel.vue'
import ShortSlide from '@/views/ShortSlide.vue'
import { useVideoStore } from '@/stores/video'
import { useUiStore } from '@/stores/ui'
import { cancelPrefetch, prefetchUpcoming } from '@/lib/prefetch'

const route = useRoute()
const router = useRouter()
const store = useVideoStore()
const ui = useUiStore()

const feed = ref<HTMLElement>()
const activeIndex = ref(0)
const commentsOpen = ref(false)
let frame = 0
let wheelLock = false

const videos = computed(() => store.videos)
const activePoster = computed(() => videos.value[activeIndex.value]?.poster ?? '')
const activeId = computed(() => videos.value[activeIndex.value]?.id ?? '')

const openComments = (): void => {
  if (!activeId.value) return
  commentsOpen.value = true
  store.ensureComments(activeId.value)
}

const closeComments = (): void => {
  commentsOpen.value = false
}

const openUser = (userId: string): void => {
  if (userId) router.push(`/user/${userId}`)
}

const requireLogin = (): void => {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

const prepare = async (id: string | undefined): Promise<void> => {
  if (!id) return
  try {
    await store.ensureVideo(id)
    await store.resolvePlay(id)
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '播放地址获取失败')
  }
}

watch(activeId, (id) => void prepare(id), { immediate: true })

/** 预取后续 8 个视频的首个分片，切换后立即中断上一轮请求 */
const prefetchAhead = async (): Promise<void> => {
  const upcoming = videos.value.slice(activeIndex.value + 1, activeIndex.value + 9)
  if (!upcoming.length) {
    cancelPrefetch()
    return
  }
  const items = await Promise.all(
    upcoming.map(async (video) => {
      try {
        return { id: video.id, mpdUrl: await store.resolvePlay(video.id) }
      } catch {
        return { id: video.id, mpdUrl: '' }
      }
    })
  )
  prefetchUpcoming(items)
}

watch(
  [activeIndex, () => videos.value.map((video) => video.id).join(',')],
  () => void prefetchAhead(),
  { immediate: true }
)

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  try {
    await store.loadFeed(true)
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '加载内容失败')
  }
})

const step = (delta: number): void => {
  const el = feed.value
  if (!el || wheelLock) return
  const next = activeIndex.value + delta
  if (next < 0 || next >= videos.value.length) return
  wheelLock = true
  el.scrollTo({ top: next * el.clientHeight, behavior: 'smooth' })
  window.setTimeout(() => {
    wheelLock = false
  }, 620)
}

const onWheel = (event: WheelEvent): void => {
  if (commentsOpen.value) return
  event.preventDefault()
  const delta = event.deltaMode === 1 ? event.deltaY * 16 : event.deltaY
  if (Math.abs(delta) < 8) return
  step(delta > 0 ? 1 : -1)
}

const onKeydown = (event: KeyboardEvent): void => {
  if (commentsOpen.value) return
  if (event.key === 'ArrowDown' || event.key === 'PageDown') {
    event.preventDefault()
    step(1)
  } else if (event.key === 'ArrowUp' || event.key === 'PageUp') {
    event.preventDefault()
    step(-1)
  }
}

const onScroll = (): void => {
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    const el = feed.value
    if (!el) return
    const height = el.clientHeight
    if (!height) return
    const index = Math.min(videos.value.length - 1, Math.max(0, Math.round(el.scrollTop / height)))
    if (index !== activeIndex.value) {
      activeIndex.value = index
      if (commentsOpen.value) closeComments()
    }
    if (index >= videos.value.length - 2) void store.loadMore()
  })
}

const pad = (value: number): string => String(value).padStart(2, '0')

const back = (): void => {
  router.push('/')
}

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  cancelPrefetch()
  if (frame) cancelAnimationFrame(frame)
})
</script>

<template>
  <div class="shorts" @wheel.capture="onWheel">
    <div class="shorts__ambient" :style="{ backgroundImage: `url(${activePoster})` }"></div>
    <div class="shorts__vignette" aria-hidden="true"></div>

    <header class="shorts-top">
      <button class="shorts-back" type="button" @click="back">
        <AppIcon name="chevronLeft" :size="16" />
        返回首页
      </button>

      <button class="shorts-search" type="button" @click="ui.openSearch()">
        <AppIcon name="search" :size="17" />
        <span>搜索你感兴趣的内容</span>
        <kbd>Ctrl K</kbd>
      </button>

      <div class="shorts-counter" aria-label="当前视频序号">
        <strong>{{ pad(activeIndex + 1) }}</strong>
        <span>/ {{ pad(videos.length) }}</span>
      </div>
    </header>

    <div ref="feed" class="short-feed" @scroll.passive="onScroll">
      <ShortSlide
        v-for="(video, index) in videos"
        :key="video.id"
        :video="video"
        :active="index === activeIndex"
        :src="store.playUrls[video.id] ?? ''"
        :comments="store.commentCount(video.id)"
        @open-comments="openComments"
        @open-user="openUser"
      />
    </div>

    <Transition name="shorts-drawer">
      <aside v-if="commentsOpen" class="shorts-comments">
        <CommentPanel
          :video-id="activeId"
          :closable="true"
          @close="closeComments"
          @select-user="openUser"
          @require-login="requireLogin"
        />
      </aside>
    </Transition>
  </div>
</template>
