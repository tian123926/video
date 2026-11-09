<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useVideoStore } from '@/stores/video'
import { useUiStore } from '@/stores/ui'
import { formatCount, type VideoItem } from '@/data/videos'

const ui = useUiStore()
const store = useVideoStore()
const router = useRouter()

const input = ref<HTMLInputElement>()
const suggestions = ['深海', '动漫', '纪录片', '治愈', '星空', '音乐']

const query = computed({
  get: () => store.searchQuery,
  set: (value: string) => {
    store.searchQuery = value
  }
})

const results = computed(() => store.searchResults.slice(0, 8))

watch(
  () => ui.searchOpen,
  async (open) => {
    if (open) {
      await nextTick()
      input.value?.focus()
    } else {
      store.searchQuery = ''
      store.searchRemote = []
    }
  }
)

let searchTimer: number | undefined
watch(
  () => store.searchQuery,
  (value) => {
    window.clearTimeout(searchTimer)
    const keyword = value.trim()
    if (!keyword) {
      store.searchRemote = []
      return
    }
    searchTimer = window.setTimeout(() => {
      void store.searchRemoteVideos(keyword)
    }, 250)
  }
)

const go = (video: VideoItem): void => {
  ui.closeSearch()
  void router.push({ name: 'watch', params: { id: video.id } })
}

const submit = (): void => {
  if (results.value[0]) go(results.value[0])
}
</script>

<template>
  <Teleport to="body">
    <Transition name="search">
      <div v-if="ui.searchOpen" class="search-layer" @keydown.esc="ui.closeSearch()">
        <div class="search-layer__backdrop" @click="ui.closeSearch()"></div>

        <button class="search-layer__close" type="button" aria-label="关闭搜索" @click="ui.closeSearch()">
          <AppIcon name="close" :size="20" />
        </button>

        <div class="search-panel">
          <div class="search-box">
            <AppIcon name="search" :size="22" class="search-box__icon" />
            <input
              ref="input"
              v-model="query"
              type="text"
              class="search-box__input"
              placeholder="搜索片名、作者或标签"
              aria-label="搜索片名、作者或标签"
              @keydown.enter="submit"
            />
            <button
              v-if="query"
              class="search-box__clear"
              type="button"
              aria-label="清空搜索"
              @click="query = ''"
            >
              <AppIcon name="close" :size="14" />
            </button>
            <span class="search-box__esc">ESC</span>
          </div>

          <Transition name="dropdown">
            <div v-if="query" class="search-dropdown">
              <template v-if="results.length">
                <button
                  v-for="video in results"
                  :key="video.id"
                  class="search-suggest"
                  type="button"
                  @click="go(video)"
                >
                  <span class="search-suggest__thumb">
                    <img :src="video.poster" :alt="video.title" />
                  </span>
                  <span class="search-suggest__body">
                    <strong>{{ video.title }}</strong>
                    <small>{{ video.author.name }} · {{ formatCount(video.views) }} 播放</small>
                  </span>
                  <span class="search-suggest__hint">
                    <AppIcon name="play" :size="13" />
                  </span>
                </button>
                <p class="search-suggest__foot">Enter 打开第一个结果 · Esc 退出搜索</p>
              </template>
              <div v-else class="search-empty">
                <span class="search-empty__icon">
                  <AppIcon name="search" :size="26" />
                </span>
                <strong>没有找到与「{{ query }}」相关的内容</strong>
                <span>换个关键词试试，比如片名、作者或者分类。</span>
              </div>
            </div>
          </Transition>

          <div v-if="!query" class="search-home">
            <div class="search-themes">
              <span class="search-label">热门搜索</span>
              <button
                v-for="item in suggestions"
                :key="item"
                class="search-chip"
                type="button"
                @click="query = item"
              >
                <AppIcon name="fire" :size="14" />
                {{ item }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
