<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import XgPlayer from '@/components/XgPlayer.vue'
import type { VideoItem } from '@/data/videos'

const props = withDefaults(
  defineProps<{ video: VideoItem; active: boolean; src: string; comments?: number }>(),
  { comments: 0 }
)

const emit = defineEmits<{ 'open-comments': [] }>()

const playerRef = ref<InstanceType<typeof XgPlayer>>()
const stage = ref<HTMLElement>()
const current = ref(0)
const duration = ref(props.video.duration)
const playing = ref(false)
const followed = ref(false)
const bar = ref<HTMLElement>()
let dragging = false

const progress = computed(() => {
  if (!duration.value) return 0
  return Math.min(100, (current.value / duration.value) * 100)
})

const fmt = (value: number): string => {
  const seconds = Math.max(0, Math.floor(value || 0))
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

const onProgress = (time: number, total: number): void => {
  current.value = time
  if (total && !Number.isNaN(total)) duration.value = total
}

const toggle = (): void => {
  playerRef.value?.toggle()
}

const fullscreen = async (): Promise<void> => {
  const el = stage.value
  if (!el) return
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await el.requestFullscreen()
  } catch {
    playerRef.value?.fullscreen()
  }
}

const seek = (clientX: number): void => {
  const el = bar.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
  playerRef.value?.seekTo(ratio * duration.value)
}

const onMove = (event: PointerEvent): void => {
  if (dragging) seek(event.clientX)
}

const onUp = (): void => {
  dragging = false
  window.removeEventListener('pointermove', onMove)
}

const onBarDown = (event: PointerEvent): void => {
  dragging = true
  seek(event.clientX)
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp, { once: true })
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
})
</script>

<template>
  <section class="short-slide" :class="{ 'short-slide--active': active }">
    <div class="short-slide__ambient" :style="{ backgroundImage: `url(${video.poster})` }"></div>

    <div ref="stage" class="short-stage">
      <img v-if="!active || !src" class="short-stage__poster" :src="video.poster" :alt="video.title" />
      <XgPlayer
        v-else
        ref="playerRef"
        :src="src"
        :poster="video.poster"
        :autoplay="true"
        :loop="true"
        :controls="false"
        @progress="onProgress"
        @playing="playing = $event"
      />

      <span class="short-stage__shade" aria-hidden="true"></span>
      <button class="short-stage__tap" type="button" aria-label="播放或暂停" @click="toggle"></button>

      <Transition name="fade">
        <span v-if="active && !playing" class="short-stage__play">
          <AppIcon name="play" :size="30" />
        </span>
      </Transition>

      <div class="short-caption">
        <div class="short-author">
          <img :src="video.author.avatar" :alt="video.author.name" />
          <div class="short-author__copy">
            <strong>{{ video.author.name }}</strong>
            <small>{{ video.category }} · ★ {{ video.rating.toFixed(1) }}</small>
          </div>
          <button
            class="short-follow"
            :class="{ 'short-follow--active': followed }"
            type="button"
            @click.stop="followed = !followed"
          >
            {{ followed ? '已关注' : '+ 关注' }}
          </button>
        </div>

        <h2>{{ video.title }}</h2>
        <p>{{ video.description }}</p>
        <div class="short-tags">
          <span v-for="tag in video.tags" :key="tag"># {{ tag }}</span>
        </div>
      </div>

      <div class="short-actions" @click.stop>
        <button
          class="short-action"
          type="button"
          aria-label="查看评论"
          @click="emit('open-comments')"
        >
          <span class="short-action__icon">
            <AppIcon name="comment" :size="20" />
          </span>
          <span class="short-action__count">{{ props.comments }}</span>
        </button>
      </div>

      <div class="short-progress" @click.stop>
        <div ref="bar" class="short-progress__bar" @pointerdown.stop.prevent="onBarDown">
          <span class="short-progress__buffer"></span>
          <span class="short-progress__played" :style="{ width: `${progress}%` }"></span>
          <span class="short-progress__knob" :style="{ left: `${progress}%` }"></span>
        </div>
        <span class="short-progress__time">{{ fmt(current) }} / {{ fmt(duration) }}</span>
        <button class="short-fullscreen" type="button" aria-label="全屏播放" @click.stop="fullscreen">
          <AppIcon name="fullscreen" :size="17" />
        </button>
      </div>
    </div>
  </section>
</template>
