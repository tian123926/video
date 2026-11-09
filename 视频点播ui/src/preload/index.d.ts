export interface DesktopApi {
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
