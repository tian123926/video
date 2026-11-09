<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { PLACEHOLDER_AVATAR, formatCount, type VideoComment } from '@/data/videos'
import { useAuthStore } from '@/stores/auth'
import { useVideoStore } from '@/stores/video'

const props = withDefaults(
  defineProps<{ videoId: string; closable?: boolean; title?: string }>(),
  { closable: false, title: '评论' }
)

const emit = defineEmits<{
  close: []
  'select-user': [userId: string]
  'require-login': []
}>()

const auth = useAuthStore()
const store = useVideoStore()

const sort = ref<'hot' | 'new'>('hot')
const draft = ref('')
const sending = ref(false)
const loadingMore = ref(false)
const replyTarget = ref<string | null>(null)
const replyDraft = ref('')
const expanded = ref<Record<string, boolean>>({})
const failedAvatars = ref<Record<string, boolean>>({})

const comments = computed(() => store.commentsFor(props.videoId))
const total = computed(() => store.commentCount(props.videoId))
const sorted = computed(() => {
  const list = [...comments.value]
  if (sort.value === 'hot') list.sort((a, b) => b.likes - a.likes)
  return list
})

onMounted(() => {
  store.ensureComments(props.videoId)
})

function avatarOf(comment: VideoComment): string {
  if (failedAvatars.value[comment.id]) return PLACEHOLDER_AVATAR
  return comment.avatar || PLACEHOLDER_AVATAR
}

function onAvatarError(comment: VideoComment): void {
  failedAvatars.value[comment.id] = true
}

function openUser(comment: VideoComment): void {
  if (comment.userId) emit('select-user', comment.userId)
}

async function submit(): Promise<void> {
  const text = draft.value.trim()
  if (!text || sending.value) return
  if (!auth.isLoggedIn) {
    emit('require-login')
    return
  }
  sending.value = true
  try {
    await store.addComment(props.videoId, text)
    draft.value = ''
  } finally {
    sending.value = false
  }
}

async function submitReply(root: VideoComment): Promise<void> {
  const text = replyDraft.value.trim()
  if (!text || sending.value) return
  if (!auth.isLoggedIn) {
    emit('require-login')
    return
  }
  sending.value = true
  try {
    await store.replyComment(props.videoId, root.id, text)
    replyDraft.value = ''
    replyTarget.value = null
    await store.loadReplies(props.videoId, root.id, 1)
    expanded.value[root.id] = true
  } finally {
    sending.value = false
  }
}

async function toggleReplies(root: VideoComment): Promise<void> {
  if (expanded.value[root.id]) {
    expanded.value[root.id] = false
    return
  }
  expanded.value[root.id] = true
  if (!root.replies?.length) await store.loadReplies(props.videoId, root.id, 1)
}

async function more(): Promise<void> {
  if (loadingMore.value) return
  loadingMore.value = true
  try {
    await store.loadMoreComments(props.videoId)
  } finally {
    loadingMore.value = false
  }
}

/** 父级容器（刷视频页）在捕获阶段拦截了滚轮，这里手动滚动评论列表 */
function onListWheel(event: WheelEvent): void {
  const el = event.currentTarget as HTMLElement | null
  if (!el) return
  const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? el.clientHeight : 1
  el.scrollTop += event.deltaY * unit
}
</script>

<template>
  <section class="cmt">
    <header class="cmt__head">
      <div class="cmt__title-row">
        <h3>{{ props.title }} <em>{{ formatCount(total) }}</em></h3>
        <button v-if="closable" class="cmt__close" type="button" aria-label="关闭" @click="emit('close')">
          <AppIcon name="close" :size="18" />
        </button>
      </div>
      <div class="cmt__tabs">
        <button type="button" :class="{ 'is-active': sort === 'hot' }" @click="sort = 'hot'">最热</button>
        <button type="button" :class="{ 'is-active': sort === 'new' }" @click="sort = 'new'">最新</button>
      </div>
    </header>

    <div class="cmt__composer">
      <img
        class="cmt__avatar"
        :src="auth.avatarUrl || PLACEHOLDER_AVATAR"
        :alt="auth.username || '我'"
      />
      <div class="cmt__composer-box">
        <input
          v-model="draft"
          class="cmt__input"
          type="text"
          maxlength="500"
          placeholder="轻轻敲醒沉睡的心灵，让我看看你的点评"
          @keyup.enter="submit"
        />
        <button class="cmt__send" type="button" :disabled="!draft.trim() || sending" @click="submit">
          <AppIcon name="send" :size="16" />
          <span>发布</span>
        </button>
      </div>
    </div>

    <div class="cmt__list" @wheel.stop.prevent="onListWheel">
      <article v-for="item in sorted" :key="item.id" class="cmt-item">
        <img
          class="cmt-item__avatar"
          :src="avatarOf(item)"
          :alt="item.name"
          @error="onAvatarError(item)"
          @click="openUser(item)"
        />
        <div class="cmt-item__body">
          <button class="cmt-item__name" type="button" @click="openUser(item)">{{ item.name }}</button>
          <p class="cmt-item__content">{{ item.content }}</p>
          <img v-if="item.imageUrl" class="cmt-item__image" :src="item.imageUrl" alt="评论图片" />
          <div class="cmt-item__meta">
            <span class="cmt-item__time">{{ item.time }}</span>
            <button
              class="cmt-item__act"
              :class="{ 'is-on': item.liked }"
              type="button"
              @click="store.toggleCommentLike(item)"
            >
              <AppIcon name="heart" :size="15" />
              <span>{{ item.likes || '' }}</span>
            </button>
            <button
              class="cmt-item__act"
              type="button"
              @click="replyTarget = replyTarget === item.id ? null : item.id"
            >
              <AppIcon name="comment" :size="15" />
              <span>回复</span>
            </button>
          </div>

          <div v-if="replyTarget === item.id" class="cmt-reply-box">
            <input
              v-model="replyDraft"
              class="cmt__input"
              type="text"
              maxlength="500"
              :placeholder="`回复 @${item.name}`"
              @keyup.enter="submitReply(item)"
            />
            <button class="cmt__send" type="button" :disabled="!replyDraft.trim() || sending" @click="submitReply(item)">
              发送
            </button>
          </div>

          <div v-if="item.replyCount" class="cmt-replies">
            <div v-if="expanded[item.id] && item.replies?.length" class="cmt-replies__list">
              <div v-for="reply in item.replies" :key="reply.id" class="cmt-reply">
                <img
                  class="cmt-reply__avatar"
                  :src="avatarOf(reply)"
                  :alt="reply.name"
                  @error="onAvatarError(reply)"
                  @click="openUser(reply)"
                />
                <div>
                  <button class="cmt-item__name" type="button" @click="openUser(reply)">{{ reply.name }}</button>
                  <p class="cmt-item__content">{{ reply.content }}</p>
                  <div class="cmt-item__meta">
                    <span class="cmt-item__time">{{ reply.time }}</span>
                    <button class="cmt-item__act" :class="{ 'is-on': reply.liked }" type="button" @click="store.toggleCommentLike(reply)">
                      <AppIcon name="heart" :size="14" />
                      <span>{{ reply.likes || '' }}</span>
                    </button>
                  </div>
                </div>
              </div>
            </div>
            <button class="cmt-replies__toggle" type="button" @click="toggleReplies(item)">
              {{ expanded[item.id] ? '收起回复' : `共 ${item.replyCount} 条回复` }}
              <AppIcon name="chevronDown" :size="14" :class="{ 'is-open': expanded[item.id] }" />
            </button>
          </div>
        </div>
      </article>

      <p v-if="!sorted.length" class="cmt__empty">还没有评论，来抢个沙发吧～</p>

      <button v-if="comments.length < total" class="cmt__more" type="button" :disabled="loadingMore" @click="more">
        {{ loadingMore ? '加载中…' : '查看更多评论' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.cmt {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #ffffff;
  color: var(--text-primary, #18191c);
}
.cmt__head {
  flex: 0 0 auto;
  padding: 16px 20px 0;
}
.cmt__title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.cmt__title-row h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}
.cmt__title-row em {
  font-style: normal;
  font-size: 13px;
  font-weight: 400;
  color: var(--text-muted, #9499a0);
  margin-left: 6px;
}
.cmt__close {
  border: 0;
  background: transparent;
  color: var(--text-muted, #9499a0);
  cursor: pointer;
  padding: 4px;
  border-radius: 50%;
}
.cmt__close:hover {
  background: var(--surface-muted, #f1f2f3);
}
.cmt__tabs {
  display: flex;
  gap: 18px;
  margin: 12px 0 8px;
}
.cmt__tabs button {
  border: 0;
  background: transparent;
  font-size: 13px;
  color: var(--text-muted, #9499a0);
  cursor: pointer;
  padding: 4px 0;
}
.cmt__tabs button.is-active {
  color: var(--text-primary, #18191c);
  font-weight: 600;
  border-bottom: 2px solid #00aeec;
}
.cmt__composer {
  flex: 0 0 auto;
  display: flex;
  gap: 10px;
  padding: 8px 20px 14px;
  border-bottom: 1px solid var(--border-subtle, #e3e5e7);
}
.cmt__avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
  flex: 0 0 auto;
  background: var(--surface-muted, #f1f2f3);
}
.cmt__composer-box {
  flex: 1;
  display: flex;
  gap: 8px;
  align-items: center;
}
.cmt__input {
  flex: 1;
  min-width: 0;
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--border-subtle, #e3e5e7);
  border-radius: 8px;
  background: var(--surface-muted, #f1f2f3);
  color: inherit;
  font-size: 13px;
  outline: none;
}
.cmt__input:focus {
  border-color: #00aeec;
  background: var(--surface, #fff);
}
.cmt__send {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 36px;
  padding: 0 14px;
  border: 0;
  border-radius: 8px;
  background: #00aeec;
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}
.cmt__send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.cmt__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 8px 20px 24px;
}
.cmt-item {
  display: flex;
  gap: 10px;
  padding: 14px 0;
  border-bottom: 1px solid var(--border-subtle, #f1f2f3);
}
.cmt-item__avatar,
.cmt-reply__avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
  flex: 0 0 auto;
  cursor: pointer;
  background: var(--surface-muted, #f1f2f3);
}
.cmt-item__body {
  flex: 1;
  min-width: 0;
}
.cmt-item__name {
  border: 0;
  background: transparent;
  padding: 0;
  font-size: 13px;
  color: var(--text-muted, #61666d);
  cursor: pointer;
  text-align: left;
}
.cmt-item__name:hover {
  color: #00aeec;
}
.cmt-item__content {
  margin: 4px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-primary, #18191c);
  white-space: pre-wrap;
  word-break: break-word;
}
.cmt-item__image {
  display: block;
  max-width: 140px;
  max-height: 140px;
  margin-top: 8px;
  border-radius: 6px;
  object-fit: cover;
}
.cmt-item__meta {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted, #9499a0);
}
.cmt-item__act {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 0;
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.cmt-item__act:hover {
  color: #00aeec;
}
.cmt-item__act.is-on {
  color: #ff6699;
}
.cmt-reply-box {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.cmt-replies {
  margin-top: 10px;
}
.cmt-replies__list {
  background: var(--surface-muted, #f6f7f8);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.cmt-reply {
  display: flex;
  gap: 8px;
}
.cmt-reply__avatar {
  width: 24px;
  height: 24px;
}
.cmt-replies__toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  border: 0;
  background: transparent;
  color: #00aeec;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.cmt-replies__toggle svg {
  transition: transform 0.2s;
}
.cmt-replies__toggle svg.is-open {
  transform: rotate(180deg);
}
.cmt__empty {
  text-align: center;
  color: var(--text-muted, #9499a0);
  font-size: 13px;
  padding: 32px 0;
}
.cmt__more {
  display: block;
  margin: 16px auto 0;
  padding: 8px 20px;
  border: 1px solid var(--border-subtle, #e3e5e7);
  border-radius: 999px;
  background: transparent;
  color: var(--text-muted, #61666d);
  font-size: 13px;
  cursor: pointer;
}
</style>
