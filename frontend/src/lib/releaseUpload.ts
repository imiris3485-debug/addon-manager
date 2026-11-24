import { api } from './api'
import type { ReleaseInfo } from './types'

// 서버가 정한 고정 이름. 다른 이름의 파일은 올리지 않는다.
export const INSTALLER_NAME = 'AddonManager-Setup.exe'
export const MANIFEST_NAME = 'latest.yml'

/** 선택한 파일 중에서 설치 파일과 latest.yml 을 이름으로 찾는다. 빠진 것이 있으면 안내 문구를 돌려준다. */
export function pickReleaseFiles(files: File[]): { installer?: File; manifest?: File; problem?: string } {
  const installer = files.find((f) => f.name === INSTALLER_NAME)
  const manifest = files.find((f) => f.name === MANIFEST_NAME)
  if (!installer || !manifest) {
    return { installer, manifest, problem: `${INSTALLER_NAME} 와 ${MANIFEST_NAME} 를 함께 선택해 주세요. (desktop/release 폴더)` }
  }
  return { installer, manifest }
}

/**
 * 설치 파일을 조각으로 나눠 올리고 게시한다.
 * 조각은 서버가 순서대로만 받는다. 중간에 실패하면 업로드를 취소하고 오류를 그대로 올려 처음부터 다시 하게 한다.
 */
export async function uploadRelease(
  installer: File,
  manifest: File,
  onProgress: (sent: number, total: number) => void,
): Promise<ReleaseInfo> {
  const latestYml = await manifest.text()
  const start = await api.adminReleaseStart()
  if (installer.size > start.maxBytes) {
    await api.adminReleaseAbort(start.uploadId).catch(() => undefined)
    throw new Error(`설치 파일이 너무 큽니다. (최대 ${Math.floor(start.maxBytes / 1024 / 1024)}MB)`)
  }

  try {
    let index = 0
    for (let offset = 0; offset < installer.size; offset += start.chunkSize) {
      await api.adminReleaseChunk(start.uploadId, index++, installer.slice(offset, offset + start.chunkSize))
      onProgress(Math.min(offset + start.chunkSize, installer.size), installer.size)
    }
  } catch (e) {
    await api.adminReleaseAbort(start.uploadId).catch(() => undefined)
    throw e
  }
  // 검증 오류(버전·해시 불일치 등)는 서버가 업로드를 남겨 두지만, 화면에서는 새로 시작하는 것이 단순하다
  try {
    return await api.adminReleaseComplete(start.uploadId, latestYml)
  } catch (e) {
    await api.adminReleaseAbort(start.uploadId).catch(() => undefined)
    throw e
  }
}
