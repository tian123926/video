import { defineStore } from 'pinia'

let toastTimer: ReturnType<typeof setTimeout> | null = null

export const useUiStore = defineStore('ui', {
  state: () => ({
    searchOpen: false,
    toastMessage: ''
  }),

  actions: {
    openSearch(): void {
      this.searchOpen = true
    },
    closeSearch(): void {
      this.searchOpen = false
    },
    toast(message: string): void {
      this.toastMessage = message
      if (toastTimer) clearTimeout(toastTimer)
      toastTimer = setTimeout(() => {
        this.toastMessage = ''
      }, 2200)
    }
  }
})
