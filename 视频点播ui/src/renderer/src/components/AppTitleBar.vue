<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

const maximized = ref(false)

onMounted(() => {
  window.desktop?.onMaximizeChange((value) => {
    maximized.value = value
  })
})

const minimize = (): void => window.desktop?.minimize()
const toggleMaximize = (): void => window.desktop?.toggleMaximize()
const close = (): void => window.desktop?.close()
</script>

<template>
  <header class="titlebar" @dblclick="toggleMaximize">
    <div class="titlebar__brand">
      <span class="brand-mark" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="15" height="15">
          <path d="M9 6.2l8.4 5.8-8.4 5.8z" fill="currentColor" />
        </svg>
      </span>
      <span class="brand-name">光影</span>
      <span class="brand-divider" aria-hidden="true"></span>
      <span class="brand-sub">VIDEO ON DEMAND</span>
    </div>

    <div class="titlebar__spacer"></div>

    <div class="window-controls" @dblclick.stop>
      <button class="window-btn" type="button" aria-label="最小化" @click="minimize">
        <AppIcon name="minimize" :size="16" />
      </button>
      <button
        class="window-btn"
        type="button"
        :aria-label="maximized ? '还原窗口' : '最大化'"
        @click="toggleMaximize"
      >
        <AppIcon :name="maximized ? 'restore' : 'maximize'" :size="15" />
      </button>
      <button class="window-btn window-btn--close" type="button" aria-label="关闭" @click="close">
        <AppIcon name="close" :size="16" />
      </button>
    </div>
  </header>
</template>
