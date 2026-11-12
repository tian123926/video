<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{ name: string; size?: number }>(), {
  size: 20
})

const ICONS: Record<string, string[]> = {
  compass: ['M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20', 'M16.2 7.8l-2.1 6.3-6.3 2.1 2.1-6.3z'],
  sparkles: [
    'M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z',
    'M19 15l.7 1.9 1.9.7-1.9.7-.7 1.9-.7-1.9-1.9-.7 1.9-.7z'
  ],
  shorts: [
    'M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z',
    'M10.4 9.1l4.2 2.9-4.2 2.9z'
  ],
  film: ['M3 5.5h18v13H3z', 'M7 5.5v13', 'M17 5.5v13', 'M3 10h4', 'M3 14h4', 'M17 10h4', 'M17 14h4'],
  tv: ['M4 6h16v12H4z', 'M8 3l4 3 4-3'],
  palette: ['M12 3a9 9 0 1 0 0 18h1a3 3 0 0 0 0-6h-1a3 3 0 0 1 0-6h6a9 9 0 0 0-6-6z'],
  mic: ['M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z', 'M5 11a7 7 0 0 0 14 0', 'M12 18v3', 'M9 21h6'],
  globe: [
    'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20',
    'M2 12h20',
    'M12 2c2.8 2.7 4.2 6.2 4.2 10s-1.4 7.3-4.2 10c-2.8-2.7-4.2-6.2-4.2-10s1.4-7.3 4.2-10z'
  ],
  music: ['M9 18V5l11-2v13', 'M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z', 'M20 16a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z'],
  book: ['M4 4.5A2.5 2.5 0 0 1 6.5 2H20v18H6.5A2.5 2.5 0 0 0 4 22z', 'M4 19.5V4.5'],
  heart: ['M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z'],
  clock: ['M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20', 'M12 7v5l3 2'],
  search: ['M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14', 'M16.5 16.5L21 21'],
  refresh: ['M21 12a9 9 0 1 1-3-6.7', 'M21 3v6h-6'],
  play: ['M8 5l11 7-11 7z'],
  pause: ['M9 5v14', 'M15 5v14'],
  chevronDown: ['M6 9l6 6 6-6'],
  chevronLeft: ['M15 5l-7 7 7 7'],
  chevronRight: ['M9 5l7 7-7 7'],
  comment: ['M21 11.5a8 8 0 0 1-8.5 8H8l-4 3v-4.6A8 8 0 0 1 12.5 3h.5a8 8 0 0 1 8 8.5z'],
  share: [
    'M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
    'M6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
    'M18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
    'M8.7 13.4l6.6 3.8',
    'M15.3 6.8l-6.6 3.8'
  ],
  fullscreen: ['M8 3H3v5', 'M16 3h5v5', 'M21 16v5h-5', 'M3 16v5h5'],
  fullscreenExit: ['M3 8h5V3', 'M21 8h-5V3', 'M21 16h-5v5', 'M3 16h5v5'],
  minimize: ['M5 12h14'],
  maximize: ['M5 5h14v14H5z'],
  restore: ['M8 8h11v11H8z', 'M8 5H5v11h3'],
  close: ['M6 6l12 12', 'M18 6l-12 12'],
  eye: ['M2 12s3.6-6 10-6 10 6 10 6-3.6 6-10 6-10-6-10-6z', 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z'],
  calendar: ['M4 6h16v14H4z', 'M4 10h16', 'M8 3v5', 'M16 3v5'],
  send: ['M21 3L10 14', 'M21 3l-7 18-4-7-7-4z'],
  plus: ['M12 5v14', 'M5 12h14'],
  check: ['M4 12l5 5L20 6'],
  more: ['M12 5v.01', 'M12 12v.01', 'M12 19v.01'],
  bookmark: ['M6 3h12v18l-6-4-6 4z'],
  arrowUp: ['M12 19V5', 'M5 12l7-7 7 7'],
  fire: ['M12 22c4 0 7-3 7-7 0-3-2-5-3.5-7-.5 1.5-1.5 2.5-3 3.5C12.5 8 13 5 10 2c.5 3-1.5 5-3 7-1.5 2-2 3.7-2 6 0 4 3 7 7 7z'],
  user: ['M12 12a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9z', 'M4 21a8 8 0 0 1 16 0'],
  volume: ['M11 5L6 9H3v6h3l5 4z', 'M15 9a4 4 0 0 1 0 6', 'M18 6a8 8 0 0 1 0 12'],
  mute: ['M11 5L6 9H3v6h3l5 4z', 'M17 9l4 6', 'M21 9l-4 6'],
  grid: ['M4 4h7v7H4z', 'M13 4h7v7h-7z', 'M4 13h7v7H4z', 'M13 13h7v7h-7z']
}

const paths = computed(() => ICONS[props.name] ?? ICONS.compass)
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <path v-for="(d, index) in paths" :key="index" :d="d" />
  </svg>
</template>
