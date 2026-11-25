import { app, BrowserWindow, dialog, ipcMain, session, shell, type IpcMainInvokeEvent } from 'electron'
import * as path from 'node:path'
import { allowedOrigin, appUrl } from './config'
import { isAllowedExternalUrl } from './externalUrl'
import { FsOps, toFsError } from './fsOps'
import { detectAddonsPaths, isForeverAddonsPath, resolveAddonsPaths } from './locator'
import { FsError } from './pathGuard'
import { RootStore } from './rootStore'
import { setupAutoUpdate } from './updater'

let mainWindow: BrowserWindow | null = null
let store: RootStore
let ops: FsOps

type Result<T> = { ok: true; value: T } | { ok: false; code: string; message: string }

const sameOrigin = (url: string): boolean => {
  try {
    return new URL(url).origin === allowedOrigin()
  } catch {
    return false
  }
}

/**
 * 외부 링크(CurseForge 상세 등)는 앱 안이 아니라 기본 브라우저로 연다.
 * https 와 CurseForge 앱 설치 주소만 허용한다(externalUrl.ts). 화면이 다른 프로그램을 실행시키지 못하게 하려는 것이다.
 */
function openExternal(url: string): void {
  if (isAllowedExternalUrl(url)) void shell.openExternal(url)
}

/**
 * 파일 작업 요청을 보낸 곳이 "우리 사이트의 메인 화면"인지 확인한다.
 * iframe 이나 다른 사이트의 화면이 이 통로를 부르면 거부한다.
 */
function assertTrusted(event: IpcMainInvokeEvent): void {
  const frame = event.senderFrame
  if (!mainWindow || event.sender !== mainWindow.webContents || !frame || frame !== event.sender.mainFrame || !sameOrigin(frame.url)) {
    throw new FsError('Invalid', '허용되지 않은 요청입니다.')
  }
}

function handle<T>(channel: string, fn: (...args: unknown[]) => Promise<T> | T): void {
  ipcMain.handle(channel, async (event, ...args): Promise<Result<T>> => {
    try {
      assertTrusted(event)
      return { ok: true, value: await fn(...args) }
    } catch (e) {
      const err = toFsError(e)
      return { ok: false, code: err.code, message: err.message }
    }
  })
}

function registerIpc(): void {
  handle('fs:detect', async () => {
    const found = await detectAddonsPaths()
    return found.map((p) => ({ key: store.addPending(p), label: p }))
  })

  handle('fs:pick', async () => {
    // 폴더 선택 창은 OS 기본 창이라 Chrome 의 시스템 폴더 제한(Program Files 등)이 없다.
    const result = await dialog.showOpenDialog(mainWindow!, { title: '와우 폴더 선택', properties: ['openDirectory'] })
    if (result.canceled || !result.filePaths[0]) return null
    let found = await resolveAddonsPaths(result.filePaths[0])
    // World of Warcraft 폴더에 여러 클라이언트가 있으면 와우 포에버(_forever_)를 우선한다
    const forever = found.filter(isForeverAddonsPath)
    if (found.length > 1 && forever.length === 1) found = forever
    if (found.length === 0) {
      throw new FsError('Invalid', '선택한 폴더에서 Interface\\AddOns 폴더를 찾지 못했습니다. 와우 설치 폴더를 선택해 주세요.')
    }
    if (found.length > 1) {
      throw new FsError('Invalid', '게임 폴더가 여러 개 있습니다. 사용할 폴더(예: _forever_)를 직접 선택해 주세요.')
    }
    return { key: store.addPending(found[0]), label: found[0] }
  })

  handle('fs:bind', (key, envId) => {
    try {
      store.bind(key, envId)
    } catch (e) {
      throw new FsError('Invalid', (e as Error).message)
    }
  })
  handle('fs:exists', (key) => ops.exists(key))
  handle('fs:list', (key, rel) => ops.list(key, rel))
  handle('fs:stat', (key, rel) => ops.stat(key, rel))
  handle('fs:read', (key, rel) => ops.read(key, rel))
  handle('fs:write', (key, rel, data) => ops.write(key, rel, data))
  handle('fs:mkdir', (key, rel) => ops.mkdir(key, rel))
  handle('fs:remove', (key, rel, recursive) => ops.remove(key, rel, recursive))
}

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    show: false,
    title: 'Addon Manager',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      // 화면(웹 페이지)이 Node 나 Electron 내부에 접근하지 못하게 격리한다
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
    },
  })

  const contents = mainWindow.webContents
  // 새 창은 앱 안에서 열지 않는다
  contents.setWindowOpenHandler(({ url }) => {
    openExternal(url)
    return { action: 'deny' }
  })
  // 다른 사이트로 이동하면 파일 기능이 있는 창에서 남의 페이지가 열리므로 막고 기본 브라우저로 보낸다
  const guardNavigation = (event: { preventDefault: () => void }, url: string) => {
    if (!sameOrigin(url)) {
      event.preventDefault()
      openExternal(url)
    }
  }
  contents.on('will-navigate', guardNavigation)
  contents.on('will-redirect', guardNavigation)

  contents.on('did-fail-load', async (_e, code, _desc, _url, isMainFrame) => {
    if (!isMainFrame || code === -3 /* 사용자가 이동을 취소한 경우 */) return
    const { response } = await dialog.showMessageBox(mainWindow!, {
      type: 'error',
      message: '서버에 연결할 수 없습니다.',
      detail: '인터넷 연결을 확인한 뒤 다시 시도하세요.',
      buttons: ['다시 시도', '종료'],
      defaultId: 0,
    })
    if (response === 0) void mainWindow?.loadURL(appUrl())
    else app.quit()
  })

  mainWindow.once('ready-to-show', () => mainWindow?.show())
  mainWindow.on('closed', () => {
    mainWindow = null
  })
  void mainWindow.loadURL(appUrl())
}

// 같은 프로그램이 두 개 뜨면 폴더 연결 파일을 동시에 쓰게 되므로 하나만 실행한다
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore()
      mainWindow.focus()
    }
  })

  app.on('web-contents-created', (_e, contents) => {
    contents.on('will-attach-webview', (event) => event.preventDefault())
  })

  void app.whenReady().then(() => {
    // 카메라·위치 등 이 앱이 쓰지 않는 권한 요청은 모두 거부한다
    session.defaultSession.setPermissionRequestHandler((_wc, _permission, callback) => callback(false))
    session.defaultSession.setPermissionCheckHandler(() => false)

    store = new RootStore(path.join(app.getPath('userData'), 'roots.json'))
    ops = new FsOps((key) => store.rootOf(key))
    registerIpc()
    createWindow()
    setupAutoUpdate(() => mainWindow)
  })

  app.on('window-all-closed', () => app.quit())
}
