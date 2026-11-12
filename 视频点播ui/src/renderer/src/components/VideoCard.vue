<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useVideoStore } from '@/stores/video'
import { formatCount, timeAgo, type VideoItem } from '@/data/videos'

const props = withDefaults(defineProps<{ video: VideoItem; compact?: boolean }>(), {
  compact: false
})

const router = useRouter()
const store = useVideoStore()

const comments = computed(() => store.commentCount(props.video.id))
/** 每张卡片独立渐进展示：封面加载完成前保持骨架 */
const loaded = ref(false)

const open = (): void => {
  router.push({ name: 'watch', params: { id: props.video.id } })
}
</script>

<template>
  <button class="video-card" type="button" @click="open" :aria-label="`播放 ${video.title}`">
    <span class="video-card__thumb">
      <span v-if="!loaded" class="skeleton video-card__thumb-skeleton"></span>
      <img
        :src="video.poster"
        :alt="video.title"
        loading="lazy"
        :class="{ 'is-loaded': loaded }"
        @load="loaded = true"
        @error="loaded = true"
      />
      <span class="video-card__shade" aria-hidden="true"></span>

      <span class="video-card__stats">
        <span class="video-card__stat">
          <AppIcon name="eye" :size="12" />
          {{ formatCount(video.views) }}
        </span>
        <span class="video-card__stat">
          <AppIcon name="comment" :size="12" />
          {{ formatCount(comments) }}
        </span>
      </span>

      <span class="video-card__duration">{{ video.durationLabel }}</span>
      <span class="video-card__play" aria-hidden="true">
        <AppIcon name="play" :size="17" />
      </span>
    </span>

    <span class="video-card__body">
      <span class="video-card__title">{{ video.title }}</span>
      <span v-if="!compact" class="video-card__meta">
        <img
          class="video-card__avatar"
          :src="video.author.avatar"
          :alt="video.author.name"
          loading="lazy"
        />
        <span class="video-card__author">{{ video.author.name }}</span>
        <span class="dot">·</span>
        <span class="video-card__time">{{ timeAgo(video.publishedAt) }}</span>
      </span>
    </span>
  </button>
</template>

<style scoped>
.video-card__thumb-skeleton {
  position: absolute;
  inset: 0;
  border-radius: inherit;
}

.video-card__thumb img {
  opacity: 0;
  transition: opacity 0.28s ease, transform 0.55s var(--ease), filter 0.3s ease;
}

.video-card__thumb img.is-loaded {
  opacity: 1;
}
</style>
