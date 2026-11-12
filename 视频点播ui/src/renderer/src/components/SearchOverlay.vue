<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { fetchHotKeywords, fetchSearchSuggest } from '@/api/video'
import { useUiStore } from '@/stores/ui'

const HISTORY_KEY = 'vod-search-history'
const MAX_HISTORY = 8

const ui = useUiStore()
const router = useRouter()
const input = ref<HTMLInputElement>()
const keyword = ref('')
const history = ref<string[]>(readHistory())
const hotWords = ref<string[]>([])
const suggestions = ref<string[]>([])
const loadingHot = ref(false)
let suggestTimer: number | undefined

function readHistory(): string[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list)
      ? list.filter((item): item is string => typeof item === 'string').slice(0, MAX_HISTORY)
      : []
  } catch {
    return []
  }
}

function persistHistory(next: string[]): void {
  history.value = next
  localStorage.setItem(HISTORY_KEY, JSON.stringify(next))
}

function remember(word: string): void {
  const value = word.trim()
  if (!value) return
  persistHistory([value, ...history.value.filter((item) => item !== value)].slice(0, MAX_HISTORY))
}

function clearHistory(): void {
  persistHistory([])
}

watch(
  () => ui.searchOpen,
  async (open) => {
    if (!open) return
    keyword.value = ''
    suggestions.value = []
    history.value = readHistory()
    await nextTick()
    input.value?.focus()
    void loadHot()
  }
)

async function loadHot(): Promise<void> {
  if (hotWords.value.length || loadingHot.value) return
  loadingHot.value = true
  try {
    hotWords.value = await fetchHotKeywords(12)
  } catch {
    hotWords.value = []
  } finally {
    loadingHot.value = false
  }
}

watch(keyword, (value) => {
  window.clearTimeout(suggestTimer)
  const kw = value.trim()
  if (!kw) {
    suggestions.value = []
    return
  }
  suggestTimer = window.setTimeout(async () => {
    try {
      suggestions.value = await fetchSearchSuggest(kw, 8)
    } catch {
      suggestions.value = []
    }
  }, 180)
})

const suggestionWords = computed(() =>
  suggestions.value.filter((word) => word.toLowerCase() !== keyword.value.trim().toLowerCase())
)

function go(word?: string): void {
  const choice = (word ?? keyword.value).trim()
  if (!choice) return
  remember(choice)
  ui.closeSearch()
  void router.push({ name: 'search', query: { keyword: choice } })
}

onBeforeUnmount(() => window.clearTimeout(suggestTimer))
</script>

<template>
  <Teleport to="body">
    <Transition name="search">
      <div v-if="ui.searchOpen" class="search-layer" @keydown.esc="ui.closeSearch()">
        <div class="search-layer__backdrop" @click="ui.closeSearch()"></div>

        <button class="search-layer__close" type="button" aria-label="关闭搜索" @click="ui.closeSearch()">
          <AppIcon name="close" :size="20" />
        </button>

        <div class="search-panel">
          <div class="search-box">
            <AppIcon name="search" :size="22" class="search-box__icon" />
            <input
              ref="input"
              v-model="keyword"
              type="text"
              class="search-box__input"
              placeholder="搜索视频、UP主"
              aria-label="搜索视频、UP主"
              @keydown.enter="go()"
            />
            <button
              v-if="keyword"
              class="search-box__clear"
              type="button"
              aria-label="清空搜索"
              @click="keyword = ''"
            >
              <AppIcon name="close" :size="14" />
            </button>
            <span class="search-box__esc">ESC</span>
          </div>

          <Transition name="dropdown">
            <div class="search-dropdown bili-search">
              <template v-if="!keyword.trim()">
                <section v-if="history.length" class="bili-search__section">
                  <header class="bili-search__head">
                    <span>搜索历史</span>
                    <button type="button" class="bili-search__clear" @click="clearHistory">
                      <AppIcon name="close" :size="12" />
                      清空
                    </button>
                  </header>
                  <button
                    v-for="word in history"
                    :key="word"
                    class="bili-search__row"
                    type="button"
                    @click="go(word)"
                  >
                    <AppIcon name="clock" :size="15" class="bili-search__icon" />
                    <span class="bili-search__word">{{ word }}</span>
                    <AppIcon name="chevronRight" :size="14" class="bili-search__arrow" />
                  </button>
                </section>

                <section class="bili-search__section">
                  <header class="bili-search__head">
                    <span>热门搜索</span>
                    <AppIcon name="fire" :size="14" class="bili-search__hot-icon" />
                  </header>
                  <div class="bili-search__hot">
                    <button
                      v-for="(word, index) in hotWords"
                      :key="word"
                      class="bili-search__hot-item"
                      :class="{ 'is-top': index < 3 }"
                      type="button"
                      @click="go(word)"
                    >
                      <em>{{ index + 1 }}</em>
                      {{ word }}
                    </button>
                  </div>
                </section>
              </template>

              <template v-else>
                <button class="bili-search__row bili-search__row--primary" type="button" @click="go()">
                  <AppIcon name="search" :size="15" class="bili-search__icon" />
                  <span class="bili-search__word">搜索「{{ keyword.trim() }}」</span>
                  <AppIcon name="chevronRight" :size="14" class="bili-search__arrow" />
                </button>
                <button
                  v-for="word in suggestionWords"
                  :key="word"
                  class="bili-search__row"
                  type="button"
                  @click="go(word)"
                >
                  <AppIcon name="search" :size="15" class="bili-search__icon" />
                  <span class="bili-search__word">{{ word }}</span>
                  <AppIcon name="chevronRight" :size="14" class="bili-search__arrow" />
                </button>
                <p v-if="!suggestionWords.length" class="bili-search__tip">
                  按 Enter 检索「{{ keyword.trim() }}」
                </p>
              </template>
            </div>
          </Transition>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.bili-search {
  padding: 6px 8px 10px;
}

.bili-search__section {
  padding: 6px 4px 4px;
}

.bili-search__section + .bili-search__section {
  margin-top: 4px;
  border-top: 1px solid var(--line);
}

.bili-search__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 8px 6px;
  font-size: 12px;
  letter-spacing: 0.04em;
  color: var(--text-3);
}

.bili-search__hot-icon {
  color: var(--accent-2);
}

.bili-search__clear {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: 0;
  background: transparent;
  padding: 2px 6px;
  border-radius: 6px;
  font-size: 11.5px;
  color: var(--text-3);
  transition: color 0.16s ease, background 0.16s ease;
}

.bili-search__clear:hover {
  color: var(--accent-2);
  background: var(--accent-soft);
}

.bili-search__row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  text-align: left;
  transition: background 0.16s ease, transform 0.16s ease;
}

.bili-search__row:hover {
  background: rgba(255, 255, 255, 0.06);
  transform: translateX(3px);
}

.bili-search__row--primary .bili-search__word {
  color: var(--accent-2);
  font-weight: 600;
}

.bili-search__icon {
  flex: none;
  color: var(--text-3);
}

.bili-search__word {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 13.5px;
  color: var(--text);
}

.bili-search__arrow {
  flex: none;
  color: var(--text-3);
  opacity: 0;
  transform: translateX(-4px);
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.bili-search__row:hover .bili-search__arrow {
  opacity: 1;
  transform: translateX(0);
}

.bili-search__hot {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 4px 8px 10px;
}

.bili-search__hot-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px 6px 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.035);
  font-size: 12.5px;
  color: var(--text-2);
  transition: border-color 0.18s ease, color 0.18s ease, background 0.18s ease;
}

.bili-search__hot-item em {
  font-style: normal;
  width: 16px;
  height: 16px;
  display: grid;
  place-items: center;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
  font-size: 10px;
  color: var(--text-3);
}

.bili-search__hot-item.is-top em {
  background: var(--accent-soft);
  color: var(--accent-2);
}

.bili-search__hot-item:hover {
  border-color: rgba(251, 113, 133, 0.4);
  color: #fff;
  background: var(--accent-soft);
}

.bili-search__tip {
  margin: 6px 12px 8px;
  font-size: 11.5px;
  color: var(--text-3);
}
</style>
