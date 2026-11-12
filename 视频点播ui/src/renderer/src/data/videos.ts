export type Category =
  | '电影'
  | '剧集'
  | '动漫'
  | '综艺'
  | '纪录片'
  | '音乐'
  | '知识'

export interface Author {
  id: string
  name: string
  avatar: string
  fansCount?: number
  likesCount?: number
}

export interface VideoItem {
  id: string
  title: string
  author: Author
  poster: string
  src: string
  category: Category
  tags: string[]
  duration: number
  durationLabel: string
  views: number
  likes: number
  rating: number
  year: number
  publishedAt: string
  description: string
  featured: boolean
  collection: 'fresh' | 'classic'
}

export interface VideoComment {
  id: string
  userId?: string
  name: string
  avatar: string
  content: string
  time: string
  likes: number
  liked: boolean
  imageUrl?: string | null
  replies?: VideoComment[]
  replyCount?: number
}

const media = (path: string): string => `${import.meta.env.BASE_URL}media/${path}`

const SRC = {
  ocean: media('videos/jellyfish.mp4'),
  blossom: media('videos/blossom.mp4'),
  forest: media('videos/forest-bunny.mp4'),
  sintel: media('videos/sintel.mp4'),
  farm: media('videos/pixel-farm.mp4')
}

const POSTER = {
  ocean: media('posters/poster-1.jpg'),
  flower: media('posters/poster-2.jpg'),
  forest: media('posters/poster-3.jpg'),
  mist: media('posters/poster-4.jpg'),
  earth: media('posters/poster-5.jpg'),
  beach: media('posters/poster-6.jpg'),
  field: media('posters/poster-7.jpg'),
  snow: media('posters/poster-8.jpg'),
  rocket: media('posters/poster-9.jpg'),
  lake: media('posters/poster-10.jpg'),
  city: media('posters/poster-11.jpg'),
  cosmos: media('posters/poster-12.jpg')
}

const AVATAR = {
  a1: media('avatars/avatar-1.jpg'),
  a2: media('avatars/avatar-2.jpg'),
  a3: media('avatars/avatar-3.jpg'),
  a4: media('avatars/avatar-4.jpg')
}

export const AUTHORS: Record<string, Author> = {
  polar: { id: 'polar', name: '极地影像', avatar: AVATAR.a1 },
  glow: { id: 'glow', name: '微光工作室', avatar: AVATAR.a2 },
  moss: { id: 'moss', name: '青苔电影', avatar: AVATAR.a3 },
  wild: { id: 'wild', name: '山野频道', avatar: AVATAR.a4 },
  dust: { id: 'dust', name: '星尘影像', avatar: AVATAR.a1 },
  shore: { id: 'shore', name: '南岸工作室', avatar: AVATAR.a2 },
  field: { id: 'field', name: '田野记', avatar: AVATAR.a3 },
  north: { id: 'north', name: '北纬影像', avatar: AVATAR.a4 },
  gravity: { id: 'gravity', name: '引力波', avatar: AVATAR.a1 },
  lake: { id: 'lake', name: '湖心社', avatar: AVATAR.a2 },
  night: { id: 'night', name: '夜行灯', avatar: AVATAR.a3 },
  pudding: { id: 'pudding', name: '布丁动画', avatar: AVATAR.a4 },
  deepblue: { id: 'deepblue', name: '深蓝计划', avatar: AVATAR.a1 },
  valley: { id: 'valley', name: '风之谷工作室', avatar: AVATAR.a2 }
}

export const formatDurationLabel = (seconds: number): string => {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

const makeVideo = (
  id: string,
  title: string,
  authorId: keyof typeof AUTHORS,
  category: Category,
  tags: string[],
  poster: string,
  src: string,
  duration: number,
  year: number,
  publishedAt: string,
  views: number,
  likes: number,
  rating: number,
  description: string,
  featured = false
): VideoItem => {
  const author = AUTHORS[authorId]
  return {
    id,
    title,
    author,
    poster,
    src,
    category,
    tags,
    duration,
    durationLabel: formatDurationLabel(duration),
    views,
    likes,
    rating,
    year,
    publishedAt,
    description,
    featured,
    collection: publishedAt.slice(0, 4) === '2026' ? 'fresh' : 'classic'
  }
}

export const PLACEHOLDER_POSTER = POSTER.ocean
export const PLACEHOLDER_AVATAR = AVATAR.a2

export const VIDEOS: VideoItem[] = [
  makeVideo('v01', '深海回响', 'polar', '纪录片', ['海洋', '自然', '视觉'], POSTER.ocean, SRC.ocean, 10, 2026, '2026-11-02T20:00:00', 1284000, 82100, 9.4, '在无光带之下，仍有千万种生命以自己的方式发光。跟随镜头沉入 3000 米深海，听见海沟深处的回响。', true),
  makeVideo('v02', '花开有时', 'glow', '电影', ['治愈', '生命', '微距'], POSTER.flower, SRC.blossom, 5, 2026, '2026-11-01T18:30:00', 964000, 60300, 9.1, '一朵花从含苞到盛放，不过五个小时的耐心。我们把镜头留在原地，等一场安静的春天。'),
  makeVideo('v03', '林间信', 'moss', '电影', ['剧情', '森林', '成长'], POSTER.forest, SRC.forest, 10, 2026, '2026-10-30T21:00:00', 842000, 52100, 8.9, '一封没有寄出的信，被风带进森林。兔子先生沿着苔藓的纹路，寻找那个写信的人。'),
  makeVideo('v04', '雾岭之上', 'wild', '纪录片', ['山脉', '云雾', '徒步'], POSTER.mist, SRC.sintel, 52, 2026, '2026-10-28T09:00:00', 1138000, 74600, 9.5, '海拔 3200 米的雾岭，一年有 200 天被云层覆盖。摄制组驻守三个月，只为拍到云海漫过山脊的十七秒。', true),
  makeVideo('v05', '蓝色家园', 'dust', '纪录片', ['地球', '生态', '航拍'], POSTER.earth, SRC.ocean, 10, 2026, '2026-10-24T16:20:00', 1503000, 95400, 9.6, '从太空回望，这颗蓝色星球像一滴悬在黑暗中的水。所有故事，都在这一滴水里面发生。'),
  makeVideo('v06', '潮汐之间', 'shore', '电影', ['海', '旅行', '夏日'], POSTER.beach, SRC.sintel, 52, 2026, '2026-10-20T11:00:00', 723000, 43900, 8.7, '涨潮时是海，退潮时是镜子。少女在海岸线走了整整一天，把心事交给潮汐保管。'),
  makeVideo('v07', '风吹麦浪', 'field', '纪录片', ['田野', '四季', '人文'], POSTER.field, SRC.farm, 10, 2026, '2026-10-15T08:30:00', 612000, 37100, 8.8, '麦子成熟的第七天，风从北边来。老农说，这是今年最好听的声音。'),
  makeVideo('v08', '雪线以北', 'north', '电影', ['雪山', '冒险', '孤独'], POSTER.snow, SRC.sintel, 52, 2026, '2026-10-08T19:40:00', 889000, 55600, 9.0, '越过雪线之后，世界只剩下风、雪和自己的呼吸。一个人，一座山，一次没有退路的攀登。'),
  makeVideo('v09', '逐星者', 'gravity', '剧集', ['科幻', '太空', '理想'], POSTER.rocket, SRC.sintel, 52, 2026, '2026-09-28T22:00:00', 1372000, 90100, 9.3, '第一集：当最后一枚火箭升空，地面上的我们，终于学会了抬头。'),
  makeVideo('v10', '静水', 'lake', '电影', ['文艺', '湖', '慢镜头'], POSTER.lake, SRC.blossom, 5, 2026, '2026-09-15T14:00:00', 486000, 29800, 8.6, '湖面没有波纹的时候，倒影比现实更清楚。一个关于等待与和解的五分钟短片。'),
  makeVideo('v11', '城市呼吸', 'night', '综艺', ['城市', '夜景', '漫游'], POSTER.city, SRC.sintel, 52, 2026, '2026-08-30T20:30:00', 1056000, 68200, 8.5, '三位主持人，一座不夜城。他们用一整夜的时间，寻找城市真正呼吸的地方。'),
  makeVideo('v12', '星河入梦', 'dust', '知识', ['宇宙', '科普', '星空'], POSTER.cosmos, SRC.ocean, 10, 2026, '2026-08-12T21:10:00', 792000, 48700, 9.2, '银河系有 1000 亿颗恒星，而你正在看的这一颗，是其中最普通、也最特别的一颗。'),
  makeVideo('v13', '像素牧场', 'pudding', '动漫', ['动画', '治愈', '田园'], POSTER.field, SRC.farm, 10, 2026, '2026-07-20T10:00:00', 1319000, 87400, 9.0, '在由方块组成的牧场里，一只兔子决定种下第一颗胡萝卜。然后，整个夏天都变了。', true),
  makeVideo('v14', '森林奇遇', 'pudding', '动漫', ['动画', '森林', '冒险'], POSTER.forest, SRC.forest, 10, 2026, '2026-06-18T17:00:00', 928000, 61000, 8.9, '迷路的小兔子误闯森林深处，遇见了会说话的蘑菇和一只不太靠谱的向导。'),
  makeVideo('v15', '蓝色星球', 'deepblue', '纪录片', ['海洋', '生物', '深潜'], POSTER.ocean, SRC.ocean, 10, 2026, '2026-05-11T15:00:00', 1624000, 108300, 9.7, '水母是地球上最古老的居民之一。它们在黑暗中漂浮了六亿年，比恐龙更早，也比人类更久。'),
  makeVideo('v16', '辛特尔', 'valley', '电影', ['奇幻', '女性', '史诗'], POSTER.mist, SRC.sintel, 52, 2026, '2026-03-02T19:00:00', 2045000, 143000, 9.6, '为了找回失去的同伴，辛特尔独自穿越雪山与荒原。这是一场关于执念与放下的漫长旅途。', true),
  makeVideo('v17', '晨光里的花', 'glow', '音乐', ['MV', '清晨', '治愈'], POSTER.flower, SRC.blossom, 5, 2026, '2026-01-20T07:30:00', 438000, 26400, 8.4, '一支没有歌词的 MV。钢琴、露水、花瓣，以及清晨六点二十分的第一束光。'),
  makeVideo('v18', '云端牧场', 'pudding', '动漫', ['动画', '田园', '轻松'], POSTER.field, SRC.farm, 10, 2025, '2025-11-15T13:00:00', 705000, 44200, 8.7, '牧场的第二季。胡萝卜大丰收，兔子们决定办一场云端派对。'),
  makeVideo('v19', '兔子的午后', 'pudding', '动漫', ['动画', '日常', '治愈'], POSTER.forest, SRC.forest, 10, 2025, '2025-09-10T16:40:00', 512000, 31800, 8.5, '阳光、草地、午睡。兔子今天什么都不想做，这也没什么不好。'),
  makeVideo('v20', '微光之城', 'night', '剧集', ['都市', '情感', '群像'], POSTER.city, SRC.sintel, 52, 2025, '2025-06-05T20:00:00', 1187000, 79300, 8.8, '一座城市有 1200 万盏灯，每一盏背后都有一个没睡的人。'),
  makeVideo('v21', '深蓝之下', 'deepblue', '纪录片', ['深海', '探索', '未知'], POSTER.earth, SRC.ocean, 10, 2025, '2025-03-18T10:00:00', 998000, 65400, 9.4, '人类对深海的了解，少于对月球表面的了解。这一次，我们把探测器的灯关掉，让黑暗自己说话。'),
  makeVideo('v22', '春分', 'glow', '音乐', ['MV', '春日', '轻音乐'], POSTER.flower, SRC.blossom, 5, 2024, '2024-12-25T12:00:00', 367000, 22100, 8.3, '白天和黑夜等长的一天，适合听一首同样安静的曲子。', true),
  makeVideo('v23', '远山淡影', 'wild', '电影', ['剧情', '回忆', '乡村'], POSTER.snow, SRC.sintel, 52, 2024, '2024-08-01T18:00:00', 654000, 41200, 8.9, '多年以后回到故乡，山还是那座山，只是看山的人已经变了。'),
  makeVideo('v24', '月光森林', 'moss', '动漫', ['动画', '夜晚', '童话'], POSTER.forest, SRC.forest, 10, 2024, '2024-02-14T21:30:00', 843000, 54700, 9.1, '只有在满月的夜晚，森林才会醒来。小兔子带着一盏灯，去参加一年一度的月光集会。')
]

export const COMMENT_SEEDS: Array<Omit<VideoComment, 'id' | 'liked'>> = [
  { name: '南风知我意', avatar: AVATAR.a2, content: '画面的质感和配乐都太舒服了，像做了一场很轻的梦。', time: '2 小时前', likes: 328 },
  { name: '一颗小行星', avatar: AVATAR.a3, content: '反复看了三遍，每一遍都能看到新的细节。', time: '5 小时前', likes: 214 },
  { name: '橘子汽水', avatar: AVATAR.a4, content: '这个镜头语言真的绝了，光影处理很有电影感。', time: '昨天 21:40', likes: 176 },
  { name: '夜航船', avatar: AVATAR.a1, content: '希望在忙碌的日子里，还能记得抬头看看天。', time: '2 天前', likes: 92 },
  { name: '海盐芝士', avatar: AVATAR.a2, content: '短片虽短，但情绪很完整。收藏了。', time: '3 天前', likes: 61 },
  { name: '白噪音', avatar: AVATAR.a3, content: '建议戴耳机看，声音设计非常细腻。', time: '1 周前', likes: 145 }
]

export function buildComments(videoId: string): VideoComment[] {
  const seed = Number(videoId.slice(1)) || 1
  return COMMENT_SEEDS.map((comment, index) => {
    const rotated = COMMENT_SEEDS[(index + seed) % COMMENT_SEEDS.length]
    return {
      ...rotated,
      id: `${videoId}-c${index + 1}`,
      likes: comment.likes + seed * 7,
      liked: false
    }
  })
}

export function formatCount(value: number): string {
  if (value >= 100000000) return `${(value / 100000000).toFixed(1)} 亿`
  if (value >= 10000) return `${(value / 10000).toFixed(1)} 万`
  return String(value)
}

export function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const minutes = Math.floor(diff / 60000)
  if (minutes < 60) return `${Math.max(1, minutes)} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`
  if (days < 30) return `${Math.floor(days / 7)} 周前`
  if (days < 365) return `${Math.floor(days / 30)} 个月前`
  return `${Math.floor(days / 365)} 年前`
}
