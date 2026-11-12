<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import VideoCard from '@/components/VideoCard.vue'
import { fetchSearchUsers, fetchSearchVideos, mapRecommendItem, type SearchUserItem } from '@/api/video'
import { PLACEHOLDER_AVATAR, formatCount, type VideoItem } from '@/data/videos'
import { useUiStore } from '@/stores/ui'

type TabKey = 'all' | 'video' | 'user'
type SortKey = 'score' | 'views' | 'latest'

const route = useRoute()
const router = useRouter()
const ui = useUiStore()

const keyword = computed(() => String(route.query.keyword ?? '').trim())
const tab = ref<TabKey>('all')
const sort = ref<SortKey>('score')
const videos = ref<VideoItem[]>([])
const users = ref<SearchUserItem[]>([])
const total = ref(0)
const page = ref(1)
const cursor = ref<string | null>(null)
const hasMore = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
const source = ref('')

const tabs: { key: TabKey; label: string }[] = [
  { key: 'all', label: '综合' },
  { key: 'video', label: '视频' },
  { key: 'user', label: '用户' }
]

const sorts: { key: SortKey; label: string }[] = [
  { key: 'score', label: '综合排序' },
  { key: 'views', label: '最多点击' },
  { key: 'latest', label: '最新发布' }
]

async function load(reset = true): Promise<void> {
  if (!keyword.value) {
    videos.value = []
    users.value = []
    total.value = 0
    return
  }
  if (reset) {
    loading.value = true
    page.value = 1
    cursor.value = null
  } else {
    loadingMore.value = true
  }
  try {
    if (tab.value === 'user') {
      users.value = await fetchSearchUsers(keyword.value, 20)
      total.value = users.value.length
      hasMore.value = false
    } else {
      const response = await fetchSearchVideos({
        keyword: keyword.value,
        page: page.value,
        size: 20,
        sort: sort.value,
        cursor: reset ? undefined : cursor.value ?? undefined
      })
      const items = response.items.map(mapRecommendItem)
      videos.value = reset ? items : [...videos.value, ...items]
      total.value = response.total
      hasMore.value = response.hasMore
      cursor.value = response.nextCursor ?? null
      source.value = response.source ?? ''
    }
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '检索失败'
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function switchTab(next: TabKey): void {
  if (tab.value === next) return
  tab.value = next
  void load(true)
}

function switchSort(next: SortKey): void {
  if (sort.value === next) return
  sort.value = next
  void load(true)
}

function loadMore(): void {
  if (!hasMore.value || loadingMore.value) return
  page.value += 1
  void load(false)
}

function openUser(id: number): void {
  void router.push({ name: 'user', params: { id: String(id) } })
}

function avatarOf(user: SearchUserItem): string {
  return user.avatarUrl || PLACEHOLDER_AVATAR
}

watch(
  () => route.query.keyword,
  () => {
    tab.value = 'all'
    sort.value = 'score'
    void load(true)
  }
)

onMounted(() => void load(true))
</script>

<template>
  <div class="search-page">
    <header class="search-page__head">
      <button class="search-page__back" type="button" aria-label="返回" @click="router.back()">
        <AppIcon name="chevronLeft" :size="18" />
      </button>
      <button class="search-page__box" type="button" @click="ui.openSearch()">
        <AppIcon name="search" :size="17" />
        <span>{{ keyword || '搜索视频、UP主' }}</span>
        <kbd>Ctrl K</kbd>
      </button>
      <p class="search-page__meta">
        找到 <strong>{{ total }}</strong> 条结果
        <span v-if="source === 'db'" class="search-page__fallback">数据库兜底</span>
      </p>
    </header>

    <nav class="search-tabs">
      <button
        v-for="item in tabs"
        :key="item.key"
        class="search-tabs__item"
        :class="{ 'is-active': tab === item.key }"
        type="button"
        @click="switchTab(item.key)"
      >
        {{ item.label }}
      </button>
    </nav>

    <div v-if="tab !== 'user'" class="search-sorts">
      <button
        v-for="item in sorts"
        :key="item.key"
        class="search-sorts__item"
        :class="{ 'is-active': sort === item.key }"
        type="button"
        @click="switchSort(item.key)"
      >
        {{ item.label }}
      </button>
      <span class="search-sorts__spacer"></span>
      <span class="search-sorts__total">共 {{ total }} 个视频</span>
    </div>

    <div v-if="loading" class="search-grid">
      <div v-for="index in 8" :key="index" class="search-skeleton">
        <span class="search-skeleton__thumb"></span>
        <span class="search-skeleton__line"></span>
        <span class="search-skeleton__line search-skeleton__line--short"></span>
      </div>
    </div>

    <template v-else-if="tab === 'user'">
      <div v-if="users.length" class="search-users">
        <button
          v-for="(user, index) in users"
          :key="user.userId"
          class="search-user search-fade"
          :style="{ animationDelay: `${index * 40}ms` }"
          type="button"
          @click="openUser(user.userId)"
        >
          <img :src="avatarOf(user)" :alt="user.username" />
          <span class="search-user__body">
            <strong>{{ user.username }}</strong>
            <small>{{ formatCount(user.fansCount) }} 粉丝 · {{ formatCount(user.likeCount) }} 获赞</small>
          </span>
          <span class="search-user__count">{{ user.videoCount ?? 0 }} 个视频</span>
          <AppIcon name="chevronRight" :size="16" class="search-user__arrow" />
        </button>
      </div>
      <div v-else class="search-empty">
        <span class="search-empty__icon">
          <AppIcon name="user" :size="26" />
        </span>
        <strong>{{ error || '没有找到相关用户' }}</strong>
        <span>{{ error ? '后端暂时不可用，请稍后重试' : '换个关键词或昵称再试试' }}</span>
      </div>
    </template>

    <template v-else>
      <div v-if="videos.length" class="search-grid">
        <div
          v-for="(video, index) in videos"
          :key="video.id"
          class="search-card search-fade"
          :style="{ animationDelay: `${Math.min(index, 12) * 35}ms` }"
        >
          <VideoCard :video="video" />
        </div>
      </div>
      <div v-else class="search-empty">
        <span class="search-empty__icon">
          <AppIcon name="search" :size="26" />
        </span>
        <strong>{{ error || `没有找到与「${keyword}」相关的视频` }}</strong>
        <span>{{ error ? '后端暂时不可用，请稍后重试' : '试试视频名、UP主或简介里的关键词' }}</span>
      </div>

      <div v-if="hasMore" class="search-more">
        <button type="button" :disabled="loadingMore" @click="loadMore">
          {{ loadingMore ? '加载中…' : '加载更多' }}
        </button>
      </div>
      <p v-else-if="videos.length" class="search-end">已经到底了 · 共 {{ total }} 个视频</p>
    </template>
  </div>
</template>

<style scoped>
.search-page {
  height: 100%;
  overflow-y: auto;
  padding: 26px 32px 64px;
}

.search-page__head {
  display: flex;
  align-items: center;
  gap: 14px;
  max-width: 1240px;
  margin: 0 auto;
}

.search-page__back {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  flex: none;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-2);
  transition: color 0.18s ease, background 0.18s ease, transform 0.18s ease;
}

.search-page__back:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.09);
  transform: translateX(-2px);
}

.search-page__box {
  flex: 1;
  min-width: 0;
  height: 44px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  border: 1px solid var(--line-strong);
  border-radius: 12px;
  background: rgba(17, 17, 23, 0.85);
  color: var(--text-2);
  font-size: 14px;
  text-align: left;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.search-page__box:hover {
  border-color: rgba(251, 113, 133, 0.45);
  box-shadow: 0 0 0 4px rgba(225, 29, 72, 0.1);
}

.search-page__box span {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--text);
}

.search-page__box kbd {
  flex: none;
  padding: 2px 6px;
  border: 1px solid var(--line);
  border-radius: 5px;
  font-size: 10px;
  color: var(--text-3);
}

.search-page__meta {
  flex: none;
  margin: 0;
  font-size: 12.5px;
  color: var(--text-3);
}

.search-page__meta strong {
  color: var(--accent-2);
  font-size: 14px;
}

.search-page__fallback {
  margin-left: 8px;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent-2);
  font-size: 10.5px;
}

.search-tabs {
  display: flex;
  gap: 26px;
  max-width: 1240px;
  margin: 22px auto 0;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
}

.search-tabs__item {
  position: relative;
  border: 0;
  background: transparent;
  padding: 4px 2px;
  font-size: 14px;
  color: var(--text-3);
  transition: color 0.18s ease;
}

.search-tabs__item:hover {
  color: var(--text);
}

.search-tabs__item.is-active {
  color: var(--text);
  font-weight: 600;
}

.search-tabs__item.is-active::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: -13px;
  width: 22px;
  height: 3px;
  border-radius: 999px;
  background: var(--accent);
  transform: translateX(-50%);
  animation: tab-in 0.25s var(--ease);
}

@keyframes tab-in {
  from {
    width: 0;
    opacity: 0;
  }
  to {
    width: 22px;
    opacity: 1;
  }
}

.search-sorts {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 1240px;
  margin: 14px auto 22px;
}

.search-sorts__item {
  border: 0;
  background: transparent;
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--text-3);
  transition: color 0.16s ease, background 0.16s ease;
}

.search-sorts__item:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.05);
}

.search-sorts__item.is-active {
  color: var(--accent-2);
  background: var(--accent-soft);
  font-weight: 600;
}

.search-sorts__spacer {
  flex: 1;
}

.search-sorts__total {
  font-size: 12px;
  color: var(--text-3);
}

.search-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(234px, 1fr));
  gap: 22px 18px;
  max-width: 1240px;
  margin: 0 auto;
}

.search-card {
  cursor: pointer;
}

.search-card :deep(.video-card) {
  width: 100%;
}

.search-fade {
  animation: card-in 0.42s var(--ease) both;
}

@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(14px) scale(0.985);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.search-users {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 920px;
  margin: 0 auto;
}

.search-user {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.03);
  text-align: left;
  transition: border-color 0.18s ease, background 0.18s ease, transform 0.18s ease;
}

.search-user:hover {
  border-color: rgba(251, 113, 133, 0.34);
  background: rgba(255, 255, 255, 0.06);
  transform: translateX(4px);
}

.search-user img {
  width: 52px;
  height: 52px;
  flex: none;
  border-radius: 50%;
  object-fit: cover;
  background: var(--surface-2);
}

.search-user__body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.search-user__body strong {
  font-size: 14.5px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.search-user__body small {
  font-size: 12px;
  color: var(--text-3);
}

.search-user__count {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
}

.search-user__arrow {
  flex: none;
  color: var(--text-3);
}

.search-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  max-width: 760px;
  margin: 40px auto 0;
  padding: 64px 0;
  border: 1px dashed var(--line);
  border-radius: 18px;
  text-align: center;
  color: var(--text-3);
}

.search-empty__icon {
  width: 58px;
  height: 58px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--surface-2);
  margin-bottom: 4px;
}

.search-empty strong {
  font-size: 15px;
  color: var(--text-2);
}

.search-empty span {
  font-size: 12.5px;
}

.search-more {
  display: flex;
  justify-content: center;
  margin-top: 30px;
}

.search-more button {
  height: 38px;
  padding: 0 30px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-2);
  font-size: 13px;
  transition: border-color 0.18s ease, color 0.18s ease, background 0.18s ease;
}

.search-more button:hover:not(:disabled) {
  border-color: rgba(251, 113, 133, 0.45);
  color: #fff;
  background: var(--accent-soft);
}

.search-more button:disabled {
  opacity: 0.55;
}

.search-end {
  margin: 30px 0 0;
  text-align: center;
  font-size: 12px;
  color: var(--text-3);
}

.search-skeleton {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.search-skeleton__thumb {
  aspect-ratio: 16 / 10;
  border-radius: 14px;
  background: var(--surface-2);
  animation: pulse 1.4s ease-in-out infinite;
}

.search-skeleton__line {
  height: 12px;
  border-radius: 6px;
  background: var(--surface-2);
  animation: pulse 1.4s ease-in-out infinite;
}

.search-skeleton__line--short {
  width: 46%;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}

@media (max-width: 720px) {
  .search-page {
    padding: 18px 16px 48px;
  }

  .search-page__meta {
    display: none;
  }

  .search-grid {
    grid-template-columns: repeat(auto-fill, minmax(158px, 1fr));
    gap: 16px 12px;
  }
}
</style>
