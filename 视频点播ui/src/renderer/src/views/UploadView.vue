<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { API_BASE } from '@/api/http'
import { abortUpload, uploadVideo, validateVideoFile } from '@/api/upload'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'

const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()

const inputRef = ref<HTMLInputElement>()
const file = ref<File | null>(null)
const dragging = ref(false)
const running = ref(false)
const progress = ref(0)
const statusText = ref('')
const uploadId = ref('')
const controller = ref<AbortController | null>(null)
const resultId = ref<number | null>(null)

const fileSize = computed(() => formatBytes(file.value?.size ?? 0))
const canUpload = computed(() => Boolean(file.value) && !running.value)

function formatBytes(bytes: number): string {
  if (bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  return `${(bytes / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

function pickFile(candidate: File): void {
  const invalid = validateVideoFile(candidate)
  if (invalid) {
    ui.toast(invalid)
    return
  }
  file.value = candidate
  progress.value = 0
  statusText.value = ''
  resultId.value = null
}

function onInputChange(event: Event): void {
  const target = event.target as HTMLInputElement
  const candidate = target.files?.[0]
  if (candidate) pickFile(candidate)
  target.value = ''
}

function onDrop(event: DragEvent): void {
  dragging.value = false
  const candidate = event.dataTransfer?.files?.[0]
  if (candidate) pickFile(candidate)
}

function reset(): void {
  file.value = null
  progress.value = 0
  statusText.value = ''
  uploadId.value = ''
  resultId.value = null
}

async function start(): Promise<void> {
  if (!file.value || running.value) return
  const invalid = validateVideoFile(file.value)
  if (invalid) {
    ui.toast(invalid)
    return
  }

  running.value = true
  progress.value = 0
  statusText.value = '准备上传…'
  resultId.value = null
  controller.value = new AbortController()

  try {
    if (!auth.isLoggedIn) {
      await router.push({ path: '/login', query: { redirect: '/upload' } })
      return
    }
    const videoId = await uploadVideo(file.value, {
      signal: controller.value.signal,
      onProgress: (ratio) => {
        progress.value = Math.min(100, Math.round(ratio * 100))
      },
      onStatus: (message) => {
        statusText.value = message
      },
      onSession: (id) => {
        uploadId.value = id
      }
    })
    resultId.value = videoId
    progress.value = 100
    ui.toast('视频已转码完成')
    await router.push(`/watch/${videoId}`)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      statusText.value = '已取消上传'
      ui.toast('已取消上传')
    } else {
      statusText.value = error instanceof Error ? error.message : '上传失败'
      ui.toast(statusText.value)
    }
  } finally {
    running.value = false
    controller.value = null
    uploadId.value = ''
  }
}

function cancel(): void {
  const id = uploadId.value
  controller.value?.abort()
  if (id) void abortUpload(id).catch(() => undefined)
}

onBeforeUnmount(() => {
  controller.value?.abort()
})
</script>

<template>
  <section class="upload-page">
    <header class="upload-head">
      <div>
        <p class="eyebrow"><span class="eyebrow__dot"></span> 创作者中心</p>
        <h1>上传视频</h1>
        <p class="upload-sub">
          支持 mp4 / mov / m4v / webm，单文件最大 2GB。分片直传 MinIO，合并后由消息队列触发 ffmpeg 转码为 HLS。
        </p>
      </div>
      <span class="upload-endpoint">{{ API_BASE }}</span>
    </header>

    <div
      class="drop-zone"
      :class="{ 'drop-zone--active': dragging, 'drop-zone--filled': Boolean(file) }"
      @click="inputRef?.click()"
      @dragenter.prevent="dragging = true"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <input
        ref="inputRef"
        type="file"
        accept=".mp4,.mov,.m4v,.webm"
        hidden
        @change="onInputChange"
      />
      <span class="drop-zone__icon">
        <AppIcon :name="file ? 'film' : 'arrowUp'" :size="28" />
      </span>
      <template v-if="file">
        <strong>{{ file.name }}</strong>
        <span>{{ fileSize }} · 点击更换文件</span>
      </template>
      <template v-else>
        <strong>拖拽视频到这里，或点击选择</strong>
        <span>本地文件只会上传到你的 MinIO 对象存储</span>
      </template>
    </div>

    <div class="upload-actions">
      <button class="primary-button" type="button" :disabled="!canUpload" @click="start">
        开始上传
      </button>
      <button v-if="running" class="ghost-button" type="button" @click="cancel">取消上传</button>
      <button v-else-if="file" class="ghost-button" type="button" @click="reset">重新选择</button>
    </div>

    <div v-if="running || statusText" class="progress-panel">
      <div class="progress-row">
        <span>{{ statusText || '准备中…' }}</span>
        <span>{{ progress }}%</span>
      </div>
      <div class="progress-track">
        <div class="progress-fill" :style="{ width: `${progress}%` }"></div>
      </div>
      <p class="progress-hint">
        上传中断后再次选择同一文件会从缺失分片继续；转码进度由 minio 服务 /upload/status 轮询。
      </p>
    </div>

    <div v-if="resultId" class="result-card">
      <div>
        <strong>视频 #{{ resultId }} 已就绪</strong>
        <span>转码产物已写入 HLS 桶，可以直接播放。</span>
      </div>
      <button class="primary-button" type="button" @click="router.push(`/watch/${resultId}`)">
        去播放
      </button>
    </div>
  </section>
</template>

<style scoped>
.upload-page {
  min-height: 100%;
  padding: 32px 40px 60px;
  display: flex;
  flex-direction: column;
  gap: 22px;
  overflow-y: auto;
}

.upload-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}

.upload-head h1 {
  margin: 8px 0 6px;
  font-size: 28px;
  letter-spacing: -0.02em;
}

.upload-sub {
  max-width: 620px;
  margin: 0;
  color: var(--text-2);
  font-size: 12.5px;
  line-height: 1.7;
}

.upload-endpoint {
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--text-3);
  font-size: 11px;
  white-space: nowrap;
}

.drop-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 240px;
  padding: 28px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius);
  background: radial-gradient(circle at 50% 0%, rgba(225, 29, 72, 0.08), transparent 55%), var(--surface);
  color: var(--text-2);
  cursor: pointer;
  transition: border-color 0.2s ease, background 0.2s ease, transform 0.2s ease;
}

.drop-zone:hover,
.drop-zone--active {
  border-color: rgba(251, 113, 133, 0.55);
  background: radial-gradient(circle at 50% 0%, rgba(225, 29, 72, 0.16), transparent 60%), var(--surface-2);
  transform: translateY(-1px);
}

.drop-zone--filled {
  border-style: solid;
}

.drop-zone strong {
  color: var(--text);
  font-size: 15px;
  font-weight: 550;
}

.drop-zone span {
  font-size: 12px;
}

.drop-zone__icon {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  margin-bottom: 4px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent-2);
}

.upload-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.upload-actions button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.progress-panel {
  padding: 16px 18px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface-2);
}

.progress-row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
  color: var(--text-2);
  font-size: 12px;
}

.progress-track {
  height: 6px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #fb7185, #e11d48);
  transition: width 0.2s ease;
}

.progress-hint {
  margin: 10px 0 0;
  color: var(--text-3);
  font-size: 11px;
  line-height: 1.6;
}

.result-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 20px;
  border: 1px solid rgba(251, 113, 133, 0.28);
  border-radius: 12px;
  background: rgba(225, 29, 72, 0.08);
}

.result-card div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.result-card strong {
  font-size: 14px;
}

.result-card span {
  color: var(--text-2);
  font-size: 12px;
}
</style>
