<script setup lang="ts">
import { useUiStore } from '@/stores/ui'
import AppIcon from '@/components/AppIcon.vue'

const ui = useUiStore()
</script>

<template>
  <Teleport to="body">
    <Transition name="toast">
      <div v-if="ui.toastMessage" class="app-toast" role="status" aria-live="polite">
        <span class="app-toast__icon">
          <AppIcon name="check" :size="14" />
        </span>
        <span>{{ ui.toastMessage }}</span>
      </div>
    </Transition>
  </Teleport>
</template>
