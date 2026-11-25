import { contextBridge, ipcRenderer } from 'electron'

// preload 는 sandbox 안에서 돈다. 다른 파일을 require 할 수 없으므로 이 파일은 electron 만 가져온다.
// 화면에 노출하는 것은 "허용된 작업 목록"뿐이고, Node/Electron 객체는 건네지 않는다.
// 실제 허용 여부(출처·경로)는 main 프로세스가 매 요청마다 다시 검사한다.
const call = (channel: string, ...args: unknown[]) => ipcRenderer.invoke(channel, ...args)

contextBridge.exposeInMainWorld('addonFs', {
  detect: () => call('fs:detect'),
  pick: () => call('fs:pick'),
  pickLibrary: () => call('fs:pickLibrary'),
  libraryInfo: () => call('fs:libraryInfo'),
  bind: (key: string, envId: number) => call('fs:bind', key, envId),
  exists: (key: string) => call('fs:exists', key),
  list: (key: string, rel: string[]) => call('fs:list', key, rel),
  stat: (key: string, rel: string[]) => call('fs:stat', key, rel),
  read: (key: string, rel: string[]) => call('fs:read', key, rel),
  write: (key: string, rel: string[], data: Uint8Array) => call('fs:write', key, rel, data),
  mkdir: (key: string, rel: string[]) => call('fs:mkdir', key, rel),
  remove: (key: string, rel: string[], recursive: boolean) => call('fs:remove', key, rel, recursive),
})
