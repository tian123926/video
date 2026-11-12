<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import VideoCard from '@/components/VideoCard.vue'
import { fetchUserProfile, fetchUserVideos, mapRecommendItem, type UserProfile } from '@/api/video'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { PLACEHOLDER_AVATAR, formatCount, type VideoItem } from '@/data/videos'

const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()

const profile = ref<UserProfile | null>(null)
const videos = ref<VideoItem[]>([])
const cursor = ref<string | null>(null)
const hasMore = ref(true)
const loading = ref(false)
const loadingMore = ref(false)
const keyword = ref('')
const scroller = ref<HTMLElement>()
const sentinel = ref<HTMLElement>()
let observer: IntersectionObserver | null = null

const avatar = computed(() => profile.value?.avatarUrl || auth.avatarUrl || PLACEHOLDER_AVATAR)
const username = computed(() => profile.value?.username || auth.username || '未登录')
const fans = computed(() => profile.value?.fansCount ?? auth.fansCount)
const likes = computed(() => profile.value?.likeCount ?? auth.likeCount)

const filtered = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) return videos.value
  return videos.value.filter((video) => video.title.toLowerCase().includes(query))
})

const loadPage = async (reset = false): Promise<void> => {
  if (!auth.userId) return
  if (reset) {
    cursor.value = null
    hasMore.value = true
    videos.value = []
  }
  const response = await fetchUserVideos(String(auth.userId), {
    count: 12,
    cursor: cursor.value
  })
  const items = response.items.map(mapRecommendItem)
  const known = new Set(videos.value.map((video) => video.id))
  videos.value = reset ? items : [...videos.value, ...items.filter((item) => !known.has(item.id))]
  cursor.value = response.nextCursor
  hasMore.value = response.hasMore
}

const loadMore = async (): Promise<void> => {
  if (loadingMore.value || loading.value || !hasMore.value) return
  loadingMore.value = true
  try {
    await loadPage(false)
  } catch {
    hasMore.value = false
  } finally {
    loadingMore.value = false
  }
}

const startObserver = (): void => {
  observer?.disconnect()
  if (!sentinel.value) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadMore()
    },
    { root: scroller.value ?? null, rootMargin: '320px 0px' }
  )
  observer.observe(sentinel.value)
}

const logout = (): void => {
  auth.logout()
  void router.replace('/')
}

onMounted(async () => {
  if (!auth.isLoggedIn) {
    await router.replace({ path: '/login', query: { redirect: '/profile' } })
    return
  }
  loading.value = true
  try {
    await auth.refreshProfile()
    if (!auth.userId) {
      await router.replace({ path: '/login', query: { redirect: '/profile' } })
      return
    }
    profile.value = await fetchUserProfile(String(auth.userId))
    await loadPage(true)
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '个人主页加载失败')
  } finally {
    loading.value = false
  }
  await nextTick()
  startObserver()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})

watch(keyword, async () => {
  if (keyword.value && hasMore.value) {
    await nextTick()
    void loadMore()
  }
})
</script>

<template>
  <div ref="scroller" class="profile-page">
    <header class="profile-hero">
      <div class="profile-hero__banner" aria-hidden="true">
        <img :src="avatar" alt="" />
      </div>

      <div class="profile-hero__row">
        <img class="profile-hero__avatar" :src="avatar" :alt="username" />

        <div class="profile-hero__copy">
          <p class="profile-hero__eyebrow">个人主页</p>
          <h1>{{ username }}</h1>
          <div class="profile-hero__stats">
            <span><b>{{ formatCount(fans) }}</b> 粉丝</span>
            <span><b>{{ formatCount(likes) }}</b> 获赞</span>
            <span><b>{{ videos.length }}</b> 作品</span>
          </div>
        </div>

        <div class="profile-hero__actions">
          <label class="profile-search">
            <AppIcon name="search" :size="16" />
            <input v-model="keyword" type="text" placeholder="在我的作品里搜索" aria-label="搜索我的作品" />
          </label>
          <button class="ghost-button" type="button" @click="logout">
            <AppIcon name="close" :size="14" />
            退出登录
          </button>
        </div>
      </div>
    </header>

    <div class="feed-bar">
      <div class="sort-tabs">
        <button class="sort-tab active" type="button">我的作品</button>
      </div>
      <div class="feed-count">
        <span class="feed-count__num">{{ filtered.length }}</span>
        部内容
        <span class="dot">·</span>
        <span class="feed-count__hint">向下滑动加载更多</span>
      </div>
    </div>

    <div v-if="filtered.length" class="video-grid">
      <VideoCard v-for="video in filtered" :key="video.id" :video="video" />
    </div>

    <div v-else-if="loading" class="video-grid">
      <div v-for="index in 6" :key="index" class="skeleton-card">
        <span class="skeleton skeleton-card__thumb"></span>
        <span class="skeleton skeleton-card__line"></span>
      </div>
    </div>

    <div v-else class="empty-state">
      <span class="empty-state__icon">
        <AppIcon name="film" :size="26" />
      </span>
      <strong>{{ keyword ? '没有匹配的作品' : '还没有作品' }}</strong>
      <span>{{ keyword ? '换个关键词试试。' : '你发布的视频会出现在这里。' }}</span>
      <button class="primary-button" type="button" @click="router.push('/upload')">去上传视频</button>
    </div>

    <div ref="sentinel" class="feed-sentinel">
      <template v-if="loadingMore">
        <span class="spinner" aria-hidden="true"></span>
        正在加载更多
      </template>
      <template v-else-if="hasMore">继续下滑加载更多</template>
      <template v-else>已经到底了</template>
    </div>
  </div>
</template>
