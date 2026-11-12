/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_VOD_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface DesktopApi {
  minimize: () => void
  toggleMaximize: () => void
  close: () => void
  onMaximizeChange: (callback: (maximized: boolean) => void) => void
}

declare global {
  interface Window {
    desktop?: DesktopApi
  }
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
