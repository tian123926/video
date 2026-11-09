<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import AppTitleBar from '@/components/AppTitleBar.vue'
import AppSidebar from '@/components/AppSidebar.vue'
import AppToast from '@/components/AppToast.vue'
import SearchOverlay from '@/components/SearchOverlay.vue'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const ui = useUiStore()

const immersive = computed(() => Boolean(route.meta.immersive))
const shorts = computed(() => Boolean(route.meta.shorts))

const onKeydown = (event: KeyboardEvent): void => {
  const modifier = event.ctrlKey || event.metaKey
  if (modifier && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    ui.openSearch()
  }
  if (event.key === 'Escape') ui.closeSearch()
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="app-shell">
    <AppTitleBar />

    <div
      class="app-body"
      :class="{ 'app-body--immersive': immersive, 'app-body--shorts': shorts }"
    >
      <AppSidebar v-if="!immersive" />
      <main class="app-main">
        <RouterView />
      </main>
    </div>

    <SearchOverlay />
    <AppToast />
  </div>
</template>
