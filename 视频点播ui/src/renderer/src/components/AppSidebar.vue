<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useVideoStore, type NavKey } from '@/stores/video'
import { useAuthStore } from '@/stores/auth'

const store = useVideoStore()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

interface NavItem {
  label: string
  icon: string
  key: 'home' | 'shorts' | 'upload' | 'profile'
  requiresAuth: boolean
}

const items: NavItem[] = [
  { label: '推荐', icon: 'compass', key: 'home', requiresAuth: false },
  { label: '刷视频', icon: 'shorts', key: 'shorts', requiresAuth: false },
  { label: '上传视频', icon: 'plus', key: 'upload', requiresAuth: true },
  { label: '我的', icon: 'user', key: 'profile', requiresAuth: true }
]

const activeKey = computed(() => {
  if (route.name === 'shorts') return 'shorts'
  if (route.name === 'upload') return 'upload'
  if (route.name === 'profile' || route.name === 'login') return 'profile'
  if (route.name === 'home') return 'home'
  return ''
})

const isActive = (item: NavItem): boolean => activeKey.value === item.key

const go = (item: NavItem): void => {
  if (item.key === 'home') {
    store.setNav('推荐' as NavKey)
    if (route.name !== 'home') void router.push('/')
    return
  }
  const target = item.key === 'shorts' ? '/shorts' : item.key === 'upload' ? '/upload' : '/profile'
  if (item.requiresAuth && !auth.isLoggedIn) {
    void router.push({ path: '/login', query: { redirect: target } })
    return
  }
  if (route.path !== target) void router.push(target)
}
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar__brand">
      <span class="sidebar__logo">
        <AppIcon name="play" :size="16" />
      </span>
      <span class="sidebar__brand-copy">
        <strong>光影放映室</strong>
        <small>GUANGYING</small>
      </span>
    </div>

    <nav class="sidebar__nav">
      <button
        v-for="item in items"
        :key="item.key"
        class="nav-item"
        :class="{ active: isActive(item) }"
        type="button"
        @click="go(item)"
      >
        <span class="nav-item__indicator" aria-hidden="true"></span>
        <AppIcon :name="item.icon" :size="18" />
        <span class="nav-item__text">{{ item.label }}</span>
      </button>
    </nav>

    <div class="sidebar__footer">
      <span class="status-dot" aria-hidden="true"></span>
      <span>{{ auth.isLoggedIn ? auth.username : '未登录' }}</span>
      <span class="sidebar__version">v0.2</span>
    </div>
  </aside>
</template>
