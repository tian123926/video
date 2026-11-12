<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import VideoCard from '@/components/VideoCard.vue'
import { useVideoStore, type SortKey } from '@/stores/video'
import { useUiStore } from '@/stores/ui'

const store = useVideoStore()
const ui = useUiStore()
const router = useRouter()

const scroller = ref<HTMLElement>()
const sentinel = ref<HTMLElement>()
const scrolled = ref(false)
let scrollFrame = 0
let observer: IntersectionObserver | null = null
let filling = false

const PAGE_SIZE = 8

const SUBTITLES: Record<string, string> = {
  推荐: '按你的口味挑选的今日影像，从深海的微光到城市的深夜。',
  精选: '编辑团队本周完整看完并留下的作品，少而精。',
  我的收藏: '你标记过的每一个片段，都在这里等你再次打开。',
  电影: '长夜需要好故事。来自世界各地的短片与电影，一次看一部。',
  剧集: '一集一个世界，适合在夜里慢慢展开的连续叙事。',
  动漫: '手绘的想象力和轻盈的情绪，给日常留一点童话。',
  综艺: '真实、松弛、有点好笑，记录人与人之间的化学反应。',
  纪录片: '把镜头交给自然与时间，看见比故事更辽阔的真实。',
  音乐: '没有台词的画面，用旋律讲完一个完整的故事。',
  知识: '用十分钟，理解一件值得知道的事。'
}

const subtitle = computed(() => SUBTITLES[store.activeNav] ?? SUBTITLES.推荐)

const sorts: Array<{ key: SortKey; label: string }> = [
  { key: 'latest', label: '最新发布' },
  { key: 'hottest', label: '最多播放' },
  { key: 'rating', label: '评分最高' }
]

const onScroll = (event: Event): void => {
  if (scrollFrame) return
  const el = event.target as HTMLElement
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    scrolled.value = el.scrollTop > 480
    void ensureFilled()
  })
}

/** 距离底部不足 500px 时继续补齐，避免"必须先上划再下滑"才能触发 */
const nearBottom = (): boolean => {
  const el = scroller.value
  if (!el) return false
  return el.scrollHeight - el.scrollTop - el.clientHeight < 500
}

const ensureFilled = async (): Promise<void> => {
  if (filling) return
  filling = true
  try {
    while (store.hasMore && !store.loading && !store.loadingMore && nearBottom()) {
      const before = store.videos.length
      await store.loadMore()
      await nextTick()
      if (store.videos.length === before) break
    }
  } finally {
    filling = false
  }
}

const startInfiniteScroll = (): void => {
  observer?.disconnect()
  if (!sentinel.value) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void ensureFilled()
    },
    { root: scroller.value ?? null, rootMargin: '400px 0px' }
  )
  observer.observe(sentinel.value)
}

const scrollToTop = (): void => {
  scroller.value?.scrollTo({ top: 0, behavior: 'smooth' })
}

const refreshFeed = async (): Promise<void> => {
  scroller.value?.scrollTo({ top: 0, behavior: 'smooth' })
  try {
    await store.refresh()
    ui.toast('内容已刷新')
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '刷新失败')
  }
}

const handleEmptyAction = async (): Promise<void> => {
  if (!store.feedError) {
    await router.push('/upload')
    return
  }
  try {
    await store.loadFeed(true)
    ui.toast('已重新连接')
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '仍然无法连接后端')
  }
}

onMounted(async () => {
  try {
    await store.loadFeed(true)
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '无法加载推荐内容')
  }
  await nextTick()
  startInfiniteScroll()
  void ensureFilled()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
})

watch(
  () => [store.activeNav, store.collection, store.sort],
  () => {
    scroller.value?.scrollTo({ top: 0, behavior: 'smooth' })
  }
)

watch(
  () => store.videos.map((video) => video.id).join(','),
  () => {
    void store.prefetchCommentCounts(store.videos.map((video) => video.id))
    void ensureFilled()
  }
)
</script>

<template>
  <div ref="scroller" class="home-scroller" @scroll.passive="onScroll">
    <div class="home">
      <header class="home-hero">
        <div class="home-hero__copy">
          <p class="eyebrow">
            <span class="eyebrow__dot" aria-hidden="true"></span>
            GUANGYING · 光影放映室
          </p>
          <h1>{{ store.activeNav }}</h1>
          <p class="home-hero__sub">{{ subtitle }}</p>
        </div>

        <div class="home-tools">
          <button class="search-trigger" type="button" @click="ui.openSearch()">
            <AppIcon name="search" :size="18" />
            <span>搜索片名、作者或标签</span>
            <kbd>Ctrl K</kbd>
          </button>
        </div>
      </header>

      <div class="feed-bar">
        <div class="sort-tabs" role="tablist" aria-label="排序方式">
          <button
            v-for="item in sorts"
            :key="item.key"
            class="sort-tab"
            :class="{ active: store.sort === item.key }"
            type="button"
            role="tab"
            :aria-selected="store.sort === item.key"
            @click="store.setSort(item.key)"
          >
            {{ item.label }}
          </button>
        </div>

        <div class="feed-count">
          <span class="feed-count__num">{{ store.filtered.length }}</span>
          部内容
          <span class="dot">·</span>
          <span class="feed-count__hint">向下滑动加载更多</span>
        </div>
      </div>

      <div v-if="store.refreshing || (store.loading && !store.videos.length)" class="video-grid">
        <div v-for="index in PAGE_SIZE" :key="index" class="skeleton-card">
          <span class="skeleton skeleton-card__thumb"></span>
          <span class="skeleton skeleton-card__line"></span>
          <span class="skeleton skeleton-card__line skeleton-card__line--short"></span>
        </div>
      </div>

      <template v-else>
        <div v-if="store.visible.length" class="video-grid">
          <VideoCard v-for="video in store.visible" :key="video.id" :video="video" />
        </div>

        <!-- 触底立即补 8 个骨架，数据到达后逐张替换 -->
        <div v-if="store.loadingMore" class="video-grid">
          <div v-for="index in PAGE_SIZE" :key="`loading-${index}`" class="skeleton-card">
            <span class="skeleton skeleton-card__thumb"></span>
            <span class="skeleton skeleton-card__line"></span>
            <span class="skeleton skeleton-card__line skeleton-card__line--short"></span>
          </div>
        </div>

        <div v-if="!store.visible.length && !store.loadingMore" class="empty-state">
          <span class="empty-state__icon">
            <AppIcon name="heart" :size="28" />
          </span>
          <strong>{{ store.feedError ? '暂时连不上后端' : '这里还空着' }}</strong>
          <span>{{ store.feedError || '上传第一个视频，转码完成后会出现在推荐流里。' }}</span>
          <button class="primary-button" type="button" @click="handleEmptyAction">
            {{ store.feedError ? '稍后重试' : '去上传视频' }}
          </button>
        </div>
      </template>

      <div ref="sentinel" class="feed-sentinel">
        <template v-if="store.loadingMore">
          <span class="spinner" aria-hidden="true"></span>
          正在加载更多内容
        </template>
        <template v-else-if="store.hasMore">
          <span class="feed-sentinel__arrow">
            <AppIcon name="chevronDown" :size="16" />
          </span>
          继续下滑加载更多
        </template>
        <template v-else>
          已经到底了 · 共 {{ store.filtered.length }} 部内容
        </template>
      </div>
    </div>

    <div class="fab-stack">
      <Transition name="back-top">
        <button
          v-if="scrolled"
          class="back-top"
          type="button"
          aria-label="回到顶部"
          @click="scrollToTop"
        >
          <AppIcon name="arrowUp" :size="18" />
        </button>
      </Transition>
      <button
        class="fab-refresh"
        type="button"
        title="刷新全部内容"
        aria-label="刷新全部内容"
        :disabled="store.refreshing"
        @click="refreshFeed"
      >
        <AppIcon name="refresh" :size="19" :class="{ spin: store.refreshing }" />
      </button>
    </div>
  </div>
</template>
