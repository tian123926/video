import { createRouter, createWebHashHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import WatchView from '@/views/WatchView.vue'
import ShortsView from '@/views/ShortsView.vue'
import UploadView from '@/views/UploadView.vue'
import ProfileView from '@/views/ProfileView.vue'
import LoginView from '@/views/LoginView.vue'
import { STORAGE_TOKEN } from '@/api/http'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView
    },
    {
      path: '/watch/:id',
      name: 'watch',
      component: WatchView,
      meta: { immersive: true }
    },
    {
      path: '/shorts',
      name: 'shorts',
      component: ShortsView,
      meta: { shorts: true }
    },
    {
      path: '/upload',
      name: 'upload',
      component: UploadView,
      meta: { requiresAuth: true }
    },
    {
      path: '/profile',
      name: 'profile',
      component: ProfileView,
      meta: { requiresAuth: true }
    },
    {
      path: '/login',
      name: 'login',
      component: LoginView
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/'
    }
  ]
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !localStorage.getItem(STORAGE_TOKEN)) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
