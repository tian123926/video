<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import CommentPanel from '@/components/CommentPanel.vue'
import XgPlayer from '@/components/XgPlayer.vue'
import { useVideoStore } from '@/stores/video'
import { useUiStore } from '@/stores/ui'
import { AUTHORS, PLACEHOLDER_POSTER, formatCount, formatDurationLabel, timeAgo } from '@/data/videos'

const route = useRoute()
const router = useRouter()
const store = useVideoStore()
const ui = useUiStore()

const videoId = computed(() => String(route.params.id))
const fallback = computed(() => ({
  id: videoId.value,
  title: '正在加载…',
  author: { id: '', name: '', avatar: AUTHORS.glow.avatar, fansCount: 0 },
  poster: PLACEHOLDER_POSTER,
  src: '',
  category: '知识' as const,
  tags: [] as string[],
  duration: 0,
  durationLabel: formatDurationLabel(0),
  views: 0,
  likes: 0,
  rating: 0,
  year: new Date().getFullYear(),
  publishedAt: new Date().toISOString(),
  description: '',
  featured: false,
  collection: 'fresh' as const
}))
const video = computed(() => store.getVideo(videoId.value) ?? fallback.value)

const playSrc = ref('')
const tab = ref<'intro' | 'comments'>('intro')
const commentsOpen = ref(false)
const followed = ref(false)

const total = computed(() => store.commentCount(videoId.value))

const protocol = computed(() => {
  const clean = playSrc.value.split('?')[0].toLowerCase()
  if (clean.endsWith('.mpd')) return 'DASH'
  if (clean.endsWith('.m3u8')) return 'HLS'
  return 'MP4'
})

const loadVideo = async (id: string): Promise<void> => {
  tab.value = 'intro'
  commentsOpen.value = false
  followed.value = false
  playSrc.value = ''
  try {
    await store.ensureVideo(id)
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '视频详情加载失败')
  }
  try {
    playSrc.value = await store.resolvePlay(id)
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '播放地址获取失败')
  }
  try {
    await store.loadComments(id)
  } catch {
    // 评论加载失败不影响播放
  }
}

watch(videoId, (id) => void loadVideo(id), { immediate: true })

const back = (): void => {
  if (window.history.length > 1) router.back()
  else void router.push('/')
}

const openComments = async (): Promise<void> => {
  tab.value = 'comments'
  commentsOpen.value = true
  await nextTick()
  window.setTimeout(() => window.dispatchEvent(new Event('resize')), 360)
  try {
    await store.loadComments(videoId.value)
  } catch {
    // 已有缓存时忽略
  }
}

const closeComments = (): void => {
  tab.value = 'intro'
  commentsOpen.value = false
  window.setTimeout(() => window.dispatchEvent(new Event('resize')), 360)
}

const selectTab = (value: 'intro' | 'comments'): void => {
  if (value === 'comments') void openComments()
  else closeComments()
}

const toggleFavorite = (): void => {
  const added = store.toggleFavorite(video.value.id)
  ui.toast(added ? '已加入我的收藏' : '已取消收藏')
}

const share = (): void => {
  const url = `${location.origin}${location.pathname}#${route.fullPath}`
  navigator.clipboard?.writeText(url).catch(() => undefined)
  ui.toast('播放链接已复制')
}

const openUser = (userId: string): void => {
  if (userId) void router.push(`/user/${userId}`)
}

const requireLogin = (): void => {
  void router.push({ path: '/login', query: { redirect: route.fullPath } })
}
</script>

<template>
  <div class="watch-stage">
    <header class="watch-bar">
      <div class="watch-bar__left">
        <button class="back-button" type="button" @click="back">
          <AppIcon name="chevronLeft" :size="16" />
          返回
        </button>
      </div>

      <div class="watch-bar__actions">
        <div class="watch-tabs" role="tablist" aria-label="视频信息与评论">
          <button
            class="watch-tab"
            :class="{ active: tab === 'intro' }"
            type="button"
            role="tab"
            :aria-selected="tab === 'intro'"
            @click="selectTab('intro')"
          >
            简介
          </button>
          <button
            class="watch-tab"
            :class="{ active: tab === 'comments' }"
            type="button"
            role="tab"
            :aria-selected="tab === 'comments'"
            @click="selectTab('comments')"
          >
            评论
            <span class="watch-tab__count">{{ formatCount(total) }}</span>
          </button>
        </div>

        <span class="watch-bar__divider" aria-hidden="true"></span>

        <button
          class="ghost-button"
          :class="{ 'ghost-button--active': store.isFavorite(video.id) }"
          type="button"
          @click="toggleFavorite"
        >
          <AppIcon name="heart" :size="16" />
          {{ store.isFavorite(video.id) ? '已收藏' : '收藏' }}
        </button>
        <button class="ghost-button" type="button" @click="share">
          <AppIcon name="share" :size="16" />
          分享
        </button>
      </div>
    </header>

    <div class="watch-body" :class="{ 'watch-body--comments': commentsOpen }">
      <section class="watch-player">
        <XgPlayer v-if="playSrc" :src="playSrc" :poster="video.poster" :autoplay="true" />
        <div v-else class="player-placeholder">
          <img :src="video.poster" :alt="video.title" />
          <span>正在获取播放地址…</span>
        </div>
        <span class="player-badge">{{ protocol }} · {{ video.durationLabel }}</span>
      </section>

      <aside class="watch-aside">
        <Transition name="panel" mode="out-in">
          <div v-if="!commentsOpen" key="info" class="watch-info">
        <div class="watch-info__author">
          <button
            class="watch-info__author-link"
            type="button"
            :disabled="!video.author.id"
            @click="openUser(video.author.id)"
          >
            <img :src="video.author.avatar" :alt="video.author.name" class="avatar avatar--lg" />
            <div class="watch-info__author-copy">
              <strong>{{ video.author.name || '匿名用户' }}</strong>
              <small>
                {{ formatCount(video.author.fansCount ?? 0) }} 粉丝
                <span class="dot">·</span>
                {{ formatCount(video.author.likesCount ?? 0) }} 获赞
              </small>
            </div>
          </button>
          <button
            class="follow-button"
            :class="{ 'follow-button--active': followed }"
            type="button"
            @click="followed = !followed"
          >
            <AppIcon :name="followed ? 'check' : 'plus'" :size="14" />
            {{ followed ? '已关注' : '关注' }}
          </button>
        </div>

        <h2 class="watch-info__title">{{ video.title }}</h2>

        <p class="watch-info__meta">
          <span>{{ formatCount(video.views) }} 播放</span>
          <span class="dot">·</span>
          <span>{{ timeAgo(video.publishedAt) }}发布</span>
        </p>

        <div class="watch-info__section">
          <p class="watch-info__label">简介</p>
          <p class="watch-info__desc">{{ video.description || '这个视频还没有简介。' }}</p>
        </div>

        <div class="tag-row">
          <span v-for="tag in video.tags" :key="tag" class="tag-chip"># {{ tag }}</span>
        </div>

        <dl class="info-grid">
          <div>
            <dt>时长</dt>
            <dd>{{ video.durationLabel }}</dd>
          </div>
          <div>
            <dt>协议</dt>
            <dd>{{ protocol }}</dd>
          </div>
          <div>
            <dt>评论</dt>
            <dd>{{ formatCount(total) }}</dd>
          </div>
          <div>
            <dt>年份</dt>
            <dd>{{ video.year }}</dd>
          </div>
        </dl>

        <button class="watch-info__comment-entry" type="button" @click="openComments">
          <AppIcon name="comment" :size="16" />
          查看全部 {{ formatCount(total) }} 条评论
          <AppIcon name="chevronRight" :size="15" />
        </button>
          </div>

          <CommentPanel
            v-else
            key="comments"
            :video-id="videoId"
            :closable="true"
            @close="closeComments"
            @select-user="openUser"
            @require-login="requireLogin"
          />
        </Transition>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.watch-info__author-link {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.watch-info__author-link:disabled {
  cursor: default;
}

.watch-info__author-link:not(:disabled):hover strong {
  color: var(--accent-2);
}

.player-placeholder {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: #05070c;
}

.player-placeholder img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.35;
  filter: blur(2px);
}

.player-placeholder span {
  position: relative;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.55);
  color: var(--text-2);
  font-size: 12px;
  letter-spacing: 0.08em;
}
</style>
