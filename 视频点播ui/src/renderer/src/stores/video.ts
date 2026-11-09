import { defineStore } from 'pinia'
import { type Category, type VideoComment, type VideoItem } from '@/data/videos'
import {
  createComment,
  fetchComments,
  fetchDetail,
  fetchPlay,
  fetchRecommend,
  mapComments,
  mapRecommendItem,
  mapReplies,
  mergeDetail,
  searchVideos
} from '@/api/video'

export type NavKey = '推荐' | '精选' | '我的收藏' | Category
export type CollectionKey = 'all' | 'fresh' | 'classic'
export type SortKey = 'latest' | 'hottest' | 'rating'

const PAGE_SIZE = 8

interface VideoState {
  videos: VideoItem[]
  activeNav: NavKey
  collection: CollectionKey
  sort: SortKey
  cursor: string | null
  hasMore: boolean
  loading: boolean
  loadingMore: boolean
  refreshing: boolean
  feedError: string | null
  searchQuery: string
  searchRemote: VideoItem[]
  searching: boolean
  favorites: string[]
  likedIds: string[]
  comments: Record<string, VideoComment[]>
  commentTotals: Record<string, number>
  detailLoaded: string[]
  playUrls: Record<string, string>
  playExpires: Record<string, number>
  playTypes: Record<string, string>
  commentPages: Record<string, number>
}

export const useVideoStore = defineStore('video', {
  state: (): VideoState => ({
    videos: [],
    activeNav: '推荐',
    collection: 'all',
    sort: 'latest',
    cursor: null,
    hasMore: true,
    loading: false,
    loadingMore: false,
    refreshing: false,
    feedError: null,
    searchQuery: '',
    searchRemote: [],
    searching: false,
    favorites: [],
    likedIds: [],
    comments: {},
    commentTotals: {},
    detailLoaded: [],
    playUrls: {},
    playExpires: {},
    playTypes: {},
    commentPages: {}
  }),

  getters: {
    filtered(state): VideoItem[] {
      let list = state.videos
      if (state.activeNav === '我的收藏') {
        list = list.filter((video) => state.favorites.includes(video.id))
      } else if (state.activeNav === '精选') {
        list = list.slice(0, 4)
      } else if (state.activeNav !== '推荐') {
        list = list.filter((video) => video.category === state.activeNav)
      }

      if (state.collection !== 'all') {
        list = list.filter((video) => video.collection === state.collection)
      }

      const sorted = [...list]
      if (state.sort === 'hottest') sorted.sort((a, b) => b.views - a.views)
      else if (state.sort === 'rating') sorted.sort((a, b) => b.rating - a.rating)
      else sorted.sort((a, b) => new Date(b.publishedAt).getTime() - new Date(a.publishedAt).getTime())
      return sorted
    },

    visible(): VideoItem[] {
      return this.filtered
    },

    featuredVideos(state): VideoItem[] {
      return state.videos.slice(0, 4)
    },

    searchResults(state): VideoItem[] {
      const keyword = state.searchQuery.trim().toLowerCase()
      if (!keyword) return []
      const local = state.videos.filter((video) => {
        const haystack = [video.title, video.author.name, video.category, ...video.tags]
          .join(' ')
          .toLowerCase()
        return haystack.includes(keyword)
      })
      const seen = new Set(state.searchRemote.map((video) => video.id))
      return [...state.searchRemote, ...local.filter((video) => !seen.has(video.id))]
    },

    isFavorite(state) {
      return (id: string): boolean => state.favorites.includes(id)
    },

    isLiked(state) {
      return (id: string): boolean => state.likedIds.includes(id)
    },

    likeCount(state) {
      return (video: VideoItem): number => video.likes + (state.likedIds.includes(video.id) ? 1 : 0)
    },

    commentsFor(state) {
      return (videoId: string): VideoComment[] => state.comments[videoId] ?? []
    }
  },

  actions: {
    getVideo(id: string): VideoItem | undefined {
      return this.videos.find((video) => video.id === id)
    },

    setNav(nav: NavKey): void {
      this.activeNav = nav
    },

    setCollection(collection: CollectionKey): void {
      this.collection = collection
    },

    setSort(sort: SortKey): void {
      this.sort = sort
    },

    async loadFeed(reset = false): Promise<void> {
      if (this.loading) return
      if (!reset && !this.hasMore) return
      this.loading = true
      try {
        const response = await fetchRecommend({
          count: PAGE_SIZE,
          cursor: reset ? null : this.cursor,
          refresh: reset
        })
        const items = response.items.map(mapRecommendItem)
        for (const item of response.items) {
          if (item.commentCount !== null && item.commentCount !== undefined) {
            this.commentTotals[String(item.videoId)] = item.commentCount
          }
        }
        if (reset) {
          this.videos = items
        } else {
          const known = new Set(this.videos.map((video) => video.id))
          this.videos.push(...items.filter((video) => !known.has(video.id)))
        }
        this.cursor = response.nextCursor
        this.hasMore = response.hasMore
        this.feedError = null
      } catch (error) {
        this.feedError = error instanceof Error ? error.message : '加载失败'
        throw error
      } finally {
        this.loading = false
      }
    },

    async loadMore(): Promise<void> {
      if (this.loadingMore || this.loading || !this.hasMore) return
      if (this.activeNav === '精选' || this.activeNav === '我的收藏') return
      this.loadingMore = true
      try {
        await this.loadFeed(false)
      } catch {
        this.hasMore = false
      } finally {
        this.loadingMore = false
      }
    },

    async refresh(): Promise<void> {
      if (this.refreshing) return
      this.refreshing = true
      try {
        await this.loadFeed(true)
      } finally {
        this.refreshing = false
      }
    },

    async searchRemoteVideos(keyword: string): Promise<void> {
      const kw = keyword.trim()
      if (!kw) {
        this.searchRemote = []
        return
      }
      this.searching = true
      try {
        const response = await searchVideos(kw, { count: 20 })
        for (const item of response.items) {
          if (item.commentCount !== null && item.commentCount !== undefined) {
            this.commentTotals[String(item.videoId)] = item.commentCount
          }
        }
        this.searchRemote = response.items.map(mapRecommendItem)
      } catch {
        this.searchRemote = []
      } finally {
        this.searching = false
      }
    },

    async ensureVideo(id: string): Promise<VideoItem | undefined> {
      const existing = this.getVideo(id)
      if (existing && this.detailLoaded.includes(id)) return existing
      const detail = await fetchDetail(id)
      const base =
        existing ??
        mapRecommendItem({
          videoId: detail.videoId,
          userId: detail.userId,
          username: detail.username,
          avatarUrl: detail.avatarUrl,
          title: detail.title,
          publishTime: detail.publishTime,
          duration: detail.duration,
          firstFrameObject: null,
          firstFrameUrl: detail.firstFrameUrl,
          firstFrameExpiresAt: null,
          viewCount: detail.viewCount,
          likeCount: detail.likeCount,
          commentCount: detail.commentCount
        })
      const merged = mergeDetail(base, detail)
      if (existing) {
        Object.assign(existing, merged)
      } else {
        this.videos.unshift(merged)
      }
      if (!this.detailLoaded.includes(id)) this.detailLoaded.push(id)
      this.commentTotals[id] = detail.commentCount
      return this.getVideo(id)
    },

    async resolvePlay(id: string): Promise<string> {
      const url = this.playUrls[id]
      const expires = this.playExpires[id] ?? 0
      if (url && expires > Date.now() + 60_000) return url
      const play = await fetchPlay(id)
      this.playUrls[id] = play.m3u8Url
      this.playExpires[id] = Date.now() + play.expiresIn * 1000
      this.playTypes[id] = play.playType
      return play.m3u8Url
    },

    async loadComments(videoId: string, page = 1): Promise<void> {
      if (page === 1 && this.comments[videoId]?.length) return
      const response = await fetchComments(videoId, { page, size: 20 })
      const mapped = mapComments(response)
      this.comments[videoId] = page === 1 ? mapped : [...(this.comments[videoId] ?? []), ...mapped]
      this.commentTotals[videoId] = response.total
      this.commentPages[videoId] = page
    },

    async loadMoreComments(videoId: string): Promise<boolean> {
      const next = (this.commentPages[videoId] ?? 1) + 1
      const loaded = this.comments[videoId]?.length ?? 0
      if (loaded >= (this.commentTotals[videoId] ?? 0)) return false
      const response = await fetchComments(videoId, { page: next, size: 20 })
      const mapped = mapComments(response)
      this.comments[videoId] = [...(this.comments[videoId] ?? []), ...mapped]
      this.commentTotals[videoId] = response.total
      this.commentPages[videoId] = next
      return mapped.length > 0
    },

    async loadReplies(videoId: string, rootId: string, page = 1): Promise<boolean> {
      const root = (this.comments[videoId] ?? []).find((item) => item.id === rootId)
      if (!root) return false
      const response = await fetchComments(videoId, { page, size: 10, parentId: Number(rootId) })
      const mapped = mapReplies(response)
      root.replies = page === 1 ? mapped : [...(root.replies ?? []), ...mapped]
      root.replyCount = response.total
      return mapped.length > 0
    },

    async prefetchCommentCounts(ids: string[]): Promise<void> {
      const queue = ids.filter((id) => this.commentTotals[id] === undefined)
      if (!queue.length) return
      const workers = Array.from({ length: Math.min(4, queue.length) }, async () => {
        while (queue.length) {
          const id = queue.shift()
          if (!id) break
          try {
            const response = await fetchComments(id, { page: 1, size: 1 })
            this.commentTotals[id] = response.total
          } catch {
            this.commentTotals[id] = 0
          }
        }
      })
      await Promise.all(workers)
    },

    ensureComments(videoId: string): void {
      if (!this.comments[videoId]) {
        void this.loadComments(videoId).catch(() => undefined)
      }
    },

    toggleFavorite(id: string): boolean {
      const index = this.favorites.indexOf(id)
      if (index >= 0) {
        this.favorites.splice(index, 1)
        return false
      }
      this.favorites.push(id)
      return true
    },

    toggleLike(id: string): void {
      const index = this.likedIds.indexOf(id)
      if (index >= 0) this.likedIds.splice(index, 1)
      else this.likedIds.push(id)
    },

    async addComment(videoId: string, content: string): Promise<void> {
      const text = content.trim()
      if (!text) return
      await createComment(videoId, text)
      this.comments[videoId] = []
      this.commentPages[videoId] = 0
      await this.loadComments(videoId, 1)
    },

    async replyComment(videoId: string, rootId: string, content: string): Promise<void> {
      const text = content.trim()
      if (!text) return
      await createComment(videoId, text, Number(rootId))
      await this.loadReplies(videoId, rootId, 1)
      this.commentTotals[videoId] = (this.commentTotals[videoId] ?? 0) + 1
    },

    toggleCommentLike(comment: VideoComment): void {
      comment.liked = !comment.liked
      comment.likes += comment.liked ? 1 : -1
    },

    commentCount(videoId: string): number {
      return this.commentTotals[videoId] ?? this.comments[videoId]?.length ?? 0
    }
  }
})
