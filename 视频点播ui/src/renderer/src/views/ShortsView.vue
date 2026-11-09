<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import ShortSlide from '@/views/ShortSlide.vue'
import { useVideoStore } from '@/stores/video'
import { useUiStore } from '@/stores/ui'
import { formatCount, type VideoComment } from '@/data/videos'
import { cancelPrefetch, prefetchUpcoming } from '@/lib/prefetch'

const router = useRouter()
const store = useVideoStore()
const ui = useUiStore()

const feed = ref<HTMLElement>()
const activeIndex = ref(0)
const commentsOpen = ref(false)
const commentText = ref('')
const expanded = ref<Record<string, boolean>>({})
let frame = 0
let wheelLock = false

const videos = computed(() => store.videos)
const activePoster = computed(() => videos.value[activeIndex.value]?.poster ?? '')
const activeId = computed(() => videos.value[activeIndex.value]?.id)
const activeComments = computed(() => (activeId.value ? store.commentsFor(activeId.value) : []))
const activeTotal = computed(() => (activeId.value ? store.commentCount(activeId.value) : 0))

const openComments = async (): Promise<void> => {
  if (!activeId.value) return
  commentsOpen.value = true
  try {
    await store.loadComments(activeId.value)
  } catch {
    // 评论加载失败不影响播放
  }
}

const closeComments = (): void => {
  commentsOpen.value = false
  commentText.value = ''
  expanded.value = {}
}

const submitComment = (): void => {
  if (!activeId.value || !commentText.value.trim()) return
  store.addComment(activeId.value, commentText.value)
  commentText.value = ''
  ui.toast('评论已发表')
}

const expandReplies = async (rootId: string): Promise<void> => {
  if (!activeId.value) return
  try {
    await store.loadReplies(activeId.value, rootId)
  } catch {
    ui.toast('回复加载失败')
  }
}

const toggleReplies = async (comment: VideoComment): Promise<void> => {
  if (expanded.value[comment.id]) {
    expanded.value[comment.id] = false
    return
  }
  expanded.value[comment.id] = true
  if (!comment.replies?.length) await expandReplies(comment.id)
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
  <div class="shorts" @wheel.capture.prevent="onWheel">
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
      />
    </div>

    <Transition name="shorts-drawer">
      <aside v-if="commentsOpen" class="shorts-comments">
        <header class="watch-comments__head">
          <div class="watch-comments__title">
            <strong>评论</strong>
            <span>{{ formatCount(activeTotal) }}</span>
          </div>
          <button
            class="watch-comments__close"
            type="button"
            aria-label="关闭评论区"
            @click="closeComments"
          >
            <AppIcon name="chevronRight" :size="16" />
          </button>
        </header>

        <div class="comment-composer comment-composer--drawer">
          <input
            v-model="commentText"
            class="comment-input"
            type="text"
            placeholder="说点什么…"
            aria-label="发表评论"
            @keydown.enter.prevent="submitComment"
          />
          <button
            class="send-button"
            type="button"
            aria-label="发送评论"
            :disabled="!commentText.trim()"
            @click="submitComment"
          >
            <AppIcon name="send" :size="16" />
          </button>
        </div>

        <div class="watch-comments__list shorts-comments__list">
          <article v-for="comment in activeComments" :key="comment.id" class="comment-item">
            <img :src="comment.avatar" :alt="comment.name" class="avatar comment-avatar" />
            <div class="comment-item__body">
              <div class="comment-item__head">
                <strong>{{ comment.name }}</strong>
                <small>{{ comment.time }}</small>
              </div>
              <p class="comment-text comment-text--clamp">{{ comment.content }}</p>
              <div class="comment-item__foot">
                <button
                  class="comment-like"
                  :class="{ 'comment-like--active': comment.liked }"
                  type="button"
                  @click="store.toggleCommentLike(comment)"
                >
                  <AppIcon name="heart" :size="13" />
                  {{ comment.likes > 0 ? comment.likes : '赞' }}
                </button>
                <button
                  v-if="(comment.replyCount ?? 0) > 0"
                  class="comment-reply-toggle"
                  :class="{ active: expanded[comment.id] }"
                  type="button"
                  @click="toggleReplies(comment)"
                >
                  <AppIcon name="comment" :size="13" />
                  {{ expanded[comment.id] ? '收起回复' : `查看 ${comment.replyCount} 条回复` }}
                </button>
              </div>

              <div v-if="expanded[comment.id]" class="reply-list">
                <p v-for="reply in comment.replies" :key="reply.id" class="reply-line">
                  <b>{{ reply.name }}</b>
                  <span>{{ reply.content }}</span>
                </p>
                <p v-if="!(comment.replies?.length)" class="reply-empty">暂无更多回复</p>
                <button
                  v-if="(comment.replyCount ?? 0) > (comment.replies?.length ?? 0)"
                  class="comment-reply-toggle comment-reply-toggle--more"
                  type="button"
                  @click="expandReplies(comment.id)"
                >
                  加载更多回复
                </button>
              </div>
            </div>
          </article>

          <p v-if="!activeComments.length" class="comment-empty">还没有评论，来说两句吧。</p>
        </div>
      </aside>
    </Transition>
  </div>
</template>
