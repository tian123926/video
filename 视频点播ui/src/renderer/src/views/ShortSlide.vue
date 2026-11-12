<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import XgPlayer from '@/components/XgPlayer.vue'
import { formatCount, type VideoItem } from '@/data/videos'

const props = withDefaults(
  defineProps<{ video: VideoItem; active: boolean; src: string; comments?: number }>(),
  { comments: 0 }
)

const emit = defineEmits<{ 'open-comments': []; 'open-user': [userId: string] }>()

const playerRef = ref<InstanceType<typeof XgPlayer>>()
const stage = ref<HTMLElement>()
const playing = ref(false)
const followed = ref(false)

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

const openUser = (): void => {
  if (props.video.author.id) emit('open-user', props.video.author.id)
}

onBeforeUnmount(() => {
  playing.value = false
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
        :controls="true"
        @playing="playing = $event"
      />

      <Transition name="fade">
        <button
          v-if="active && !playing"
          class="short-stage__play"
          type="button"
          aria-label="播放"
          @click.stop="toggle"
        >
          <AppIcon name="play" :size="30" />
        </button>
      </Transition>

      <div class="short-caption">
        <div class="short-author">
          <img
            :src="video.author.avatar"
            :alt="video.author.name"
            class="short-author__avatar"
            @click.stop="openUser"
          />
          <div class="short-author__copy">
            <strong @click.stop="openUser">{{ video.author.name }}</strong>
            <small>
              {{ formatCount(video.author.fansCount ?? 0) }} 粉丝
              <span class="dot">·</span>
              {{ formatCount(video.author.likesCount ?? 0) }} 获赞
            </small>
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
        <p v-if="video.description">{{ video.description }}</p>
        <div v-if="video.tags.length" class="short-tags">
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
        <button class="short-action" type="button" aria-label="全屏" @click="fullscreen">
          <span class="short-action__icon">
            <AppIcon name="fullscreen" :size="20" />
          </span>
        </button>
      </div>
    </div>
  </section>
</template>
