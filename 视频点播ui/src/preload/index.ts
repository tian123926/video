import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('desktop', {
  minimize: (): void => ipcRenderer.send('window:minimize'),
  toggleMaximize: (): void => ipcRenderer.send('window:toggle-maximize'),
  close: (): void => ipcRenderer.send('window:close'),
  onMaximizeChange: (callback: (maximized: boolean) => void): void => {
    ipcRenderer.on('window:maximized', (_event, maximized: boolean) => callback(maximized))
  }
})
