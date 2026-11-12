import { request } from './http'

export interface LoginResponse {
  token: string
  userId: number
  username: string
  avatarUrl: string | null
  fansCount: number
  likeCount: number
}

/** 本系统使用 uid（用户ID）直接登录 */
export async function loginByUid(uid: number): Promise<LoginResponse> {
  return request<LoginResponse>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ uid })
  })
}

export interface UserProfile {
  userId: number
  username: string
  avatarUrl: string | null
  fansCount: number
  likeCount: number
}

export async function fetchMyProfile(): Promise<UserProfile> {
  return request<UserProfile>('/auth/me')
}
