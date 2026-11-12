/**
 * 极简 DASH 索引解析：只处理后端按模板生成的静态 SegmentList 索引。
 * 后端不存储索引文件，每次播放时按 uid/vid 实时拼装并逐个签名分片。
 */

export interface DashTrack {
  contentType: 'video' | 'audio'
  mimeType: string
  codecs: string
  initUrl: string
  segmentUrls: string[]
  /** 单个分片时长（秒） */
  segmentDuration: number
  bandwidth: number
  width?: number
  height?: number
}

export interface DashManifest {
  duration: number
  video: DashTrack
  audio: DashTrack | null
}

const ISO_DURATION = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?$/

function parseIsoDuration(value: string | null): number {
  if (!value) return 0
  const match = ISO_DURATION.exec(value.trim())
  if (!match) return 0
  const [, d, h, m, s] = match
  const seconds =
    Number(d ?? 0) * 86400 + Number(h ?? 0) * 3600 + Number(m ?? 0) * 60 + Number(s ?? 0)
  return Number.isFinite(seconds) ? seconds : 0
}

function resolveUrl(value: string, base: string): string {
  try {
    return new URL(value, base).toString()
  } catch {
    return value
  }
}

function readTrack(node: Element, contentType: 'video' | 'audio', base: string): DashTrack | null {
  const representations = Array.from(node.getElementsByTagName('Representation'))
  if (!representations.length) return null

  const representation = representations.reduce((best, current) => {
    const bestRate = Number(best.getAttribute('bandwidth') ?? 0)
    const currentRate = Number(current.getAttribute('bandwidth') ?? 0)
    return currentRate > bestRate ? current : best
  })

  // SegmentList 可能挂在 Representation 或 AdaptationSet 上
  const list =
    representation.getElementsByTagName('SegmentList')[0] ??
    node.getElementsByTagName('SegmentList')[0]
  if (!list) return null

  const timescale = Number(list.getAttribute('timescale') ?? 1) || 1
  const duration = Number(list.getAttribute('duration') ?? 0)
  const segmentDuration = duration > 0 ? duration / timescale : 2.5

  const initNode = list.getElementsByTagName('Initialization')[0]
  const initSource = initNode?.getAttribute('sourceURL') ?? initNode?.getAttribute('sourceUrl')
  if (!initSource) return null

  const segmentUrls = Array.from(list.getElementsByTagName('SegmentURL'))
    .map((item) => item.getAttribute('media') ?? item.getAttribute('mediaRange'))
    .filter((item): item is string => Boolean(item))
    .map((item) => resolveUrl(item, base))
  if (!segmentUrls.length) return null

  const mimeType = node.getAttribute('mimeType') ?? (contentType === 'video' ? 'video/mp4' : 'audio/mp4')
  const codecs =
    representation.getAttribute('codecs') ??
    node.getAttribute('codecs') ??
    (contentType === 'video' ? 'avc1.64001F' : 'mp4a.40.2')

  return {
    contentType,
    mimeType,
    codecs,
    initUrl: resolveUrl(initSource, base),
    segmentUrls,
    segmentDuration,
    bandwidth: Number(representation.getAttribute('bandwidth') ?? 0),
    width: Number(representation.getAttribute('width') ?? 0) || undefined,
    height: Number(representation.getAttribute('height') ?? 0) || undefined
  }
}

export function parseMpd(xml: string, mpdUrl: string): DashManifest {
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  const parseError = doc.getElementsByTagName('parsererror')[0]
  if (parseError) throw new Error('索引解析失败')

  const mpd = doc.getElementsByTagName('MPD')[0]
  if (!mpd) throw new Error('索引缺少 MPD 节点')
  const duration =
    parseIsoDuration(mpd.getAttribute('mediaPresentationDuration')) ||
    parseIsoDuration(mpd.getElementsByTagName('Period')[0]?.getAttribute('duration') ?? null)

  let video: DashTrack | null = null
  let audio: DashTrack | null = null
  for (const node of Array.from(doc.getElementsByTagName('AdaptationSet'))) {
    const mimeType = node.getAttribute('mimeType') ?? ''
    const contentType = node.getAttribute('contentType') ?? ''
    const isVideo = contentType === 'video' || mimeType.startsWith('video')
    const isAudio = contentType === 'audio' || mimeType.startsWith('audio')
    if (isVideo && !video) video = readTrack(node, 'video', mpdUrl)
    if (isAudio && !audio) audio = readTrack(node, 'audio', mpdUrl)
  }

  if (!video) throw new Error('索引缺少视频轨道')
  return { duration, video, audio }
}
