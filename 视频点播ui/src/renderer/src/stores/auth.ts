import { defineStore } from 'pinia'
import { fetchMyProfile, loginByUid } from '../api/auth'
import { STORAGE_TOKEN, STORAGE_USERNAME } from '../api/http'

interface AuthState {
  token: string
  userId: number | null
  username: string
  avatarUrl: string | null
  fansCount: number
  likeCount: number
  pending: Promise<void> | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(STORAGE_TOKEN) || '',
    userId: null,
    username: localStorage.getItem(STORAGE_USERNAME) || '',
    avatarUrl: null,
    fansCount: 0,
    likeCount: 0,
    pending: null
  }),

  getters: {
    isLoggedIn: (state) => Boolean(state.token)
  },

  actions: {
    async loginByUid(uid: number): Promise<void> {
      const result = await loginByUid(uid)
      this.token = result.token
      this.userId = result.userId
      this.username = result.username
      this.avatarUrl = result.avatarUrl
      this.fansCount = result.fansCount ?? 0
      this.likeCount = result.likeCount ?? 0
      localStorage.setItem(STORAGE_TOKEN, result.token)
      localStorage.setItem(STORAGE_USERNAME, result.username)
    },

    async refreshProfile(): Promise<void> {
      if (!this.token) return
      try {
        const profile = await fetchMyProfile()
        this.userId = profile.userId
        this.username = profile.username
        this.avatarUrl = profile.avatarUrl
        this.fansCount = profile.fansCount ?? 0
        this.likeCount = profile.likeCount ?? 0
        localStorage.setItem(STORAGE_USERNAME, profile.username)
      } catch {
        // 令牌失效时保持未登录态，由页面引导重新登录
        this.logout()
      }
    },

    logout(): void {
      this.token = ''
      this.userId = null
      this.username = ''
      this.avatarUrl = null
      this.fansCount = 0
      this.likeCount = 0
      localStorage.removeItem(STORAGE_TOKEN)
      localStorage.removeItem(STORAGE_USERNAME)
    }
  }
})
