<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import XgPlayer from '@/components/XgPlayer.vue'
import { useVideoStore } from '@/stores/video'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { AUTHORS, PLACEHOLDER_AVATAR, PLACEHOLDER_POSTER, formatCount, formatDurationLabel, timeAgo, type VideoComment } from '@/data/videos'

const route = useRoute()
const router = useRouter()
const store = useVideoStore()
const ui = useUiStore()
const auth = useAuthStore()

const videoId = computed(() => String(route.params.id))
const fallback = computed(() => ({
  id: videoId.value,
  title: '正在加载…',
  author: { id: '', name: '', avatar: AUTHORS.glow.avatar, fansCount: 0 },
  poster: PLACEHOLDER_POSTER,
  src: '',
  category: '纪录片' as const,
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
const commentText = ref('')
const listEl = ref<HTMLElement>()
const loadingMore = ref(false)
const expanded = ref<Record<string, boolean>>({})

const comments = computed(() => store.commentsFor(videoId.value))
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
  commentText.value = ''
  expanded.value = {}
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

const submitComment = async (): Promise<void> => {
  const text = commentText.value.trim()
  if (!text) return
  if (!auth.isLoggedIn) {
    ui.toast('请先使用 UID 登录')
    void router.push('/login')
    return
  }
  try {
    await store.addComment(video.value.id, text)
    commentText.value = ''
    ui.toast('评论已发表')
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '评论发表失败')
  }
}

const loadMore = async (): Promise<void> => {
  if (loadingMore.value) return
  loadingMore.value = true
  try {
    await store.loadMoreComments(videoId.value)
  } catch {
    ui.toast('更多评论加载失败')
  } finally {
    loadingMore.value = false
  }
}

const onListScroll = (event: Event): void => {
  const el = event.target as HTMLElement
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 160) void loadMore()
}

const expandReplies = async (rootId: string): Promise<void> => {
  try {
    await store.loadReplies(videoId.value, rootId)
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
          <img :src="video.author.avatar" :alt="video.author.name" class="avatar avatar--lg" />
          <div class="watch-info__author-copy">
            <strong>{{ video.author.name || '匿名用户' }}</strong>
            <small>{{ formatCount(video.author.fansCount ?? 0) }} 粉丝</small>
          </div>
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
          <span class="dot">·</span>
          <span>{{ video.category }}</span>
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

          <div v-else key="comments" class="watch-comments">
          <header class="watch-comments__head">
            <div class="watch-comments__title">
              <strong>评论</strong>
              <span>{{ formatCount(total) }}</span>
            </div>
            <button class="watch-comments__close" type="button" aria-label="收起评论区" @click="closeComments">
              <AppIcon name="chevronRight" :size="16" />
            </button>
          </header>

          <div class="comment-composer comment-composer--drawer">
            <img
              :src="auth.avatarUrl || PLACEHOLDER_AVATAR"
              alt="我的头像"
              class="avatar comment-avatar"
            />
            <input
              v-model="commentText"
              class="comment-input"
              type="text"
              :placeholder="auth.isLoggedIn ? '发一条友善的评论…' : '登录后可以发表评论'"
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

          <div ref="listEl" class="watch-comments__list" @scroll.passive="onListScroll">
            <article v-for="comment in comments" :key="comment.id" class="comment-item">
              <img :src="comment.avatar" :alt="comment.name" class="avatar comment-avatar" />
              <div class="comment-item__body">
                <div class="comment-item__head">
                  <strong>{{ comment.name }}</strong>
                  <small>{{ comment.time }}</small>
                </div>
                <p class="comment-text comment-text--clamp">{{ comment.content }}</p>
                <img
                  v-if="comment.imageUrl"
                  class="comment-image"
                  :src="comment.imageUrl"
                  alt="评论图片"
                  loading="lazy"
                />
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

            <p v-if="!comments.length" class="comment-empty">还没有评论，来说两句吧。</p>

            <div class="comment-loadmore">
              <button
                v-if="comments.length && comments.length < total"
                class="ghost-button"
                type="button"
                :disabled="loadingMore"
                @click="loadMore"
              >
                {{ loadingMore ? '加载中…' : '加载更多评论' }}
              </button>
              <span v-else-if="comments.length">已经到底了</span>
            </div>
          </div>
          </div>
        </Transition>
      </aside>
    </div>
  </div>
</template>

<style scoped>
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
