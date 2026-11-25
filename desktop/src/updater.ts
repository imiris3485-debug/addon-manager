import { app, dialog, type BrowserWindow } from 'electron'
import { autoUpdater } from 'electron-updater'
import { appUrl } from './config'

// 새 빌드가 올라왔는지 주기적으로 확인한다. 앱을 켜 둔 채로도 업데이트를 받을 수 있게 한다.
const CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000

/**
 * 업데이트 정보(latest.yml)와 설치 파일은 우리 사이트의 /downloads 에서만 받는다.
 * 주소는 화면(웹)이 바꿀 수 없고 배포본에서는 고정된 값(config.ts)이다.
 * ⚠️ 코드 서명이 없으므로 서버가 받쳐 주는 무결성은 HTTPS 와 latest.yml 의 SHA-512 뿐이다.
 *    그래서 게시 권한(관리자)을 보호하는 것이 업데이트 보안의 핵심이다.
 */
export interface UpdateStatus {
  /** dev: 설치본이 아님 / latest: 최신 / downloaded: 새 버전을 받았고 재시작하면 적용 / error: 확인 실패 */
  state: 'dev' | 'latest' | 'downloaded' | 'error'
  current: string
  latest?: string
  message?: string
}

/** 지금 바로 새 버전이 있는지 확인하고, 있으면 받아 둔다(사용자가 버튼을 눌렀을 때). */
export async function checkNow(): Promise<UpdateStatus> {
  const current = app.getVersion()
  if (!app.isPackaged) return { state: 'dev', current }
  try {
    const result = await autoUpdater.checkForUpdates()
    const latest = result?.updateInfo.version
    if (!result || !result.isUpdateAvailable) return { state: 'latest', current, latest }
    if (result.downloadPromise) await result.downloadPromise
    return { state: 'downloaded', current, latest }
  } catch (e) {
    // 확인 실패의 이유(서버에 게시된 앱이 없음, 네트워크 등)를 사용자가 볼 수 있게 돌려준다
    return { state: 'error', current, message: e instanceof Error ? e.message : String(e) }
  }
}

/** 받아 둔 새 버전을 적용하고 다시 시작한다. */
export function installNow(): void {
  autoUpdater.quitAndInstall(true, true)
}

export function setupAutoUpdate(getWindow: () => BrowserWindow | null): void {
  if (!app.isPackaged) return    // 개발 중에는 설치본이 아니라서 확인하지 않는다

  autoUpdater.setFeedURL({ provider: 'generic', url: `${appUrl()}/downloads` })
  autoUpdater.autoDownload = true
  autoUpdater.autoInstallOnAppQuit = true        // "나중에"를 골라도 앱을 닫을 때 설치된다
  autoUpdater.allowDowngrade = false             // 낮은 버전으로 되돌리는 업데이트는 받지 않는다

  autoUpdater.on('error', (e) => {
    // 서버에 아직 게시된 앱이 없거나 네트워크가 끊긴 경우가 대부분이라 사용자에게 알리지 않는다
    console.error('업데이트 확인 실패:', e?.message ?? e)
  })

  autoUpdater.on('update-downloaded', async (info) => {
    const window = getWindow()
    const options = {
      type: 'info' as const,
      title: '업데이트',
      message: `새 버전(${info.version})이 준비되었습니다.`,
      detail: '지금 다시 시작하면 바로 적용됩니다. 나중에 선택해도 앱을 닫을 때 자동으로 적용됩니다.',
      buttons: ['지금 재시작', '나중에'],
      defaultId: 0,
      cancelId: 1,
    }
    const { response } = window ? await dialog.showMessageBox(window, options) : await dialog.showMessageBox(options)
    if (response === 0) autoUpdater.quitAndInstall(true, true)
  })

  const check = () => {
    autoUpdater.checkForUpdates().catch(() => undefined)
  }
  check()
  setInterval(check, CHECK_INTERVAL_MS).unref()
}
