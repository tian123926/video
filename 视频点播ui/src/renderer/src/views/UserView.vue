<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import VideoCard from '@/components/VideoCard.vue'
import { fetchUserProfile, fetchUserVideos, mapRecommendItem, type UserProfile } from '@/api/video'
import { PLACEHOLDER_AVATAR, formatCount, type VideoItem } from '@/data/videos'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const router = useRouter()
const ui = useUiStore()

const userId = computed(() => String(route.params.id))
const profile = ref<UserProfile | null>(null)
const videos = ref<VideoItem[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const cursor = ref<string | null>(null)
const hasMore = ref(false)
const avatarFailed = ref(false)

const avatar = computed(() => {
  if (avatarFailed.value || !profile.value?.avatarUrl) return PLACEHOLDER_AVATAR
  return profile.value.avatarUrl
})

const load = async (): Promise<void> => {
  loading.value = true
  avatarFailed.value = false
  cursor.value = null
  hasMore.value = false
  videos.value = []
  try {
    const [info, page] = await Promise.all([
      fetchUserProfile(userId.value),
      fetchUserVideos(userId.value, { count: 12 })
    ])
    profile.value = info
    videos.value = page.items.map(mapRecommendItem)
    cursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '用户主页加载失败')
  } finally {
    loading.value = false
  }
}

const loadMore = async (): Promise<void> => {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  try {
    const page = await fetchUserVideos(userId.value, { count: 12, cursor: cursor.value })
    videos.value = [...videos.value, ...page.items.map(mapRecommendItem)]
    cursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '作品加载失败')
  } finally {
    loadingMore.value = false
  }
}

watch(userId, () => void load(), { immediate: true })

const back = (): void => {
  if (window.history.length > 1) router.back()
  else void router.push('/')
}
</script>

<template>
  <div class="home-scroller">
    <div class="home">
      <button class="back-button user-back" type="button" @click="back">
        <AppIcon name="chevronLeft" :size="16" />
        返回
      </button>

      <header class="user-hero">
        <img
          class="user-hero__avatar"
          :src="avatar"
          :alt="profile?.username || '用户头像'"
          @error="avatarFailed = true"
        />
        <div class="user-hero__copy">
          <h1>{{ profile?.username || (loading ? '加载中…' : '未知用户') }}</h1>
          <p class="user-hero__stats">
            <span><strong>{{ formatCount(profile?.fansCount ?? 0) }}</strong> 粉丝</span>
            <span class="dot">·</span>
            <span><strong>{{ formatCount(profile?.likeCount ?? 0) }}</strong> 获赞</span>
            <span class="dot">·</span>
            <span><strong>{{ videos.length }}</strong> 作品</span>
          </p>
        </div>
      </header>

      <div class="feed-bar">
        <div class="sort-tabs">
          <span class="sort-tab active">TA 的作品</span>
        </div>
      </div>

      <div v-if="videos.length" class="video-grid">
        <VideoCard v-for="video in videos" :key="video.id" :video="video" />
      </div>

      <div v-else-if="!loading" class="empty-state">
        <span class="empty-state__icon">
          <AppIcon name="user" :size="28" />
        </span>
        <strong>还没有作品</strong>
        <span>这个用户暂时没有已发布的视频。</span>
      </div>

      <div class="feed-sentinel">
        <template v-if="loading">
          <span class="spinner" aria-hidden="true"></span>
          正在加载主页
        </template>
        <button v-else-if="hasMore" class="ghost-button" type="button" :disabled="loadingMore" @click="loadMore">
          {{ loadingMore ? '加载中…' : '加载更多作品' }}
        </button>
        <template v-else-if="videos.length">已经到底了</template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.user-back {
  margin-bottom: 14px;
}

.user-hero {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 22px 24px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.04), rgba(255, 255, 255, 0.01));
}

.user-hero__avatar {
  width: 84px;
  height: 84px;
  flex: none;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid rgba(255, 255, 255, 0.12);
  background: var(--surface-2);
}

.user-hero__copy {
  min-width: 0;
}

.user-hero__copy h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 650;
  letter-spacing: 0.02em;
}

.user-hero__stats {
  display: flex;
  align-items: baseline;
  gap: 7px;
  margin: 8px 0 0;
  font-size: 12.5px;
  color: var(--text-2);
}

.user-hero__stats strong {
  font-size: 15px;
  color: var(--text);
}
</style>
