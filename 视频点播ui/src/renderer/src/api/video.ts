import { request } from './http'
import {
  PLACEHOLDER_AVATAR,
  PLACEHOLDER_POSTER,
  formatDurationLabel,
  timeAgo,
  type VideoComment,
  type VideoItem
} from '../data/videos'

export interface RecommendItem {
  videoId: number
  userId: number
  username: string
  avatarUrl: string | null
  title: string
  publishTime: string | null
  duration: number
  firstFrameObject: string | null
  firstFrameUrl: string | null
  firstFrameExpiresAt: string | null
  viewCount: number | null
  likeCount: number | null
  commentCount: number | null
  authorFansCount: number | null
  authorLikeCount: number | null
}

export interface RecommendResponse {
  items: RecommendItem[]
  nextCursor: string | null
  hasMore: boolean
}

export interface VideoDetailResponse {
  videoId: number
  userId: number
  username: string
  avatarUrl: string | null
  authorFansCount: number | null
  title: string
  description: string | null
  publishTime: string | null
  duration: number
  status: number
  firstFrameUrl: string | null
  commentCount: number
  viewCount: number | null
  likeCount: number | null
  authorLikeCount: number | null
}

export interface PlayResponse {
  playType: string
  m3u8Url: string
  expiresIn: number
  segmentsSigned: boolean
  preloadTime: number
  minCachedTime: number
}

interface CommentReply {
  commentId: number
  userId: number
  username: string
  avatarUrl: string | null
  content: string
  imageUrl: string | null
  createdAt: string
}

interface CommentItem extends CommentReply {
  likeCount: number
  replyCount: number
  replies: CommentReply[]
}

export interface CommentPage {
  total: number
  page: number
  size: number
  items: CommentItem[]
}

export interface ObjectUrlResponse {
  url: string
  expiresAt: string
}

export interface UserProfile {
  userId: number
  username: string
  avatarUrl: string | null
  fansCount: number
  likeCount: number
}

export async function fetchRecommend(params: {
  count?: number
  cursor?: string | null
  refresh?: boolean
}): Promise<RecommendResponse> {
  const search = new URLSearchParams()
  if (params.count) search.set('count', String(params.count))
  if (params.cursor) search.set('cursor', params.cursor)
  if (params.refresh) search.set('refresh', 'true')
  return request<RecommendResponse>(`/recommend?${search.toString()}`)
}

export async function fetchDetail(videoId: string): Promise<VideoDetailResponse> {
  return request<VideoDetailResponse>(`/${encodeURIComponent(videoId)}/detail`)
}

export async function searchVideos(
  keyword: string,
  params: { count?: number; cursor?: string | null } = {}
): Promise<RecommendResponse> {
  const search = new URLSearchParams({ keyword })
  if (params.count) search.set('count', String(params.count))
  if (params.cursor) search.set('cursor', params.cursor)
  return request<RecommendResponse>(`/search?${search.toString()}`)
}

export async function createComment(
  videoId: string,
  content: string,
  parentId = 0
): Promise<{ commentId: number }> {
  return request<{ commentId: number }>(`/${encodeURIComponent(videoId)}/comments`, {
    method: 'POST',
    body: JSON.stringify({ content, parentId })
  })
}

export async function fetchPlay(videoId: string): Promise<PlayResponse> {
  return request<PlayResponse>(`/${encodeURIComponent(videoId)}/play`)
}

export async function fetchComments(
  videoId: string,
  params: { page?: number; size?: number; parentId?: number } = {}
): Promise<CommentPage> {
  const search = new URLSearchParams({
    page: String(params.page ?? 1),
    size: String(params.size ?? 20),
    parentId: String(params.parentId ?? 0)
  })
  return request<CommentPage>(`/${encodeURIComponent(videoId)}/comments?${search.toString()}`)
}

export async function fetchObjectUrl(
  bucket: string,
  objectName: string,
  expires = 600
): Promise<ObjectUrlResponse> {
  const search = new URLSearchParams({ bucket, objectName, expires: String(expires) })
  return request<ObjectUrlResponse>(`/object-url?${search.toString()}`)
}

export async function fetchUserProfile(userId: string): Promise<UserProfile> {
  return request<UserProfile>(`/user/${encodeURIComponent(userId)}`)
}

export async function fetchUserVideos(
  userId: string,
  params: { count?: number; cursor?: string | null } = {}
): Promise<RecommendResponse> {
  const search = new URLSearchParams()
  if (params.count) search.set('count', String(params.count))
  if (params.cursor) search.set('cursor', params.cursor)
  return request<RecommendResponse>(`/user/${encodeURIComponent(userId)}/videos?${search.toString()}`)
}

function toYear(iso: string | null): number {
  const year = iso ? Number(iso.slice(0, 4)) : 0
  return Number.isFinite(year) && year > 0 ? year : new Date().getFullYear()
}

export function mapRecommendItem(item: RecommendItem): VideoItem {
  const seconds = Math.max(0, Math.round(item.duration || 0))
  return {
    id: String(item.videoId),
    title: item.title || '未命名视频',
    author: {
      id: String(item.userId),
      name: item.username || '匿名用户',
      avatar: item.avatarUrl || PLACEHOLDER_AVATAR,
      fansCount: item.authorFansCount ?? 0,
      likesCount: item.authorLikeCount ?? 0
    },
    poster: item.firstFrameUrl || PLACEHOLDER_POSTER,
    src: '',
    category: '知识',
    tags: [],
    duration: seconds,
    durationLabel: formatDurationLabel(seconds),
    views: item.viewCount ?? 0,
    likes: item.likeCount ?? 0,
    rating: 0,
    year: toYear(item.publishTime),
    publishedAt: item.publishTime || new Date().toISOString(),
    description: '',
    featured: false,
    collection: 'fresh'
  }
}

export function mergeDetail(video: VideoItem, detail: VideoDetailResponse): VideoItem {
  const seconds = Math.max(0, Math.round(detail.duration || 0))
  return {
    ...video,
    title: detail.title || video.title,
    author: {
      id: String(detail.userId),
      name: detail.username || video.author.name,
      avatar: detail.avatarUrl || video.author.avatar || PLACEHOLDER_AVATAR,
      fansCount: detail.authorFansCount ?? video.author.fansCount ?? 0,
      likesCount: detail.authorLikeCount ?? video.author.likesCount ?? 0
    },
    poster: detail.firstFrameUrl || video.poster || PLACEHOLDER_POSTER,
    duration: seconds || video.duration,
    durationLabel: seconds ? formatDurationLabel(seconds) : video.durationLabel,
    views: detail.viewCount ?? video.views,
    likes: detail.likeCount ?? video.likes,
    description: detail.description || '',
    year: toYear(detail.publishTime),
    publishedAt: detail.publishTime || video.publishedAt
  }
}

function mapReply(reply: CommentReply): VideoComment {
  return {
    id: String(reply.commentId),
    userId: String(reply.userId),
    name: reply.username || '匿名用户',
    avatar: reply.avatarUrl || PLACEHOLDER_AVATAR,
    content: reply.content,
    time: timeAgo(reply.createdAt),
    likes: 0,
    liked: false,
    imageUrl: reply.imageUrl || null
  }
}

export function mapComments(page: CommentPage): VideoComment[] {
  return page.items.map((item) => ({
    id: String(item.commentId),
    userId: String(item.userId),
    name: item.username || '匿名用户',
    avatar: item.avatarUrl || PLACEHOLDER_AVATAR,
    content: item.content,
    time: timeAgo(item.createdAt),
    likes: item.likeCount,
    liked: false,
    imageUrl: item.imageUrl || null,
    replyCount: item.replyCount,
    replies: (item.replies || []).map(mapReply)
  }))
}

export function mapReplies(page: CommentPage): VideoComment[] {
  return page.items.map(mapReply)
}
