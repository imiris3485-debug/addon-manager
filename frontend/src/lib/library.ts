import { api } from './api'
import { prepareZip, writePrepared } from './installer'
import type { LibraryAddon, LocalFolder } from './types'

// ---------------------------------------------------------------------------------------------
// 설치한 내용의 기록. 이 앱이 자료실에서 설치할 때 폴더 이름 → 내용 해시를 이 PC(브라우저/앱)에 남겨 두고,
// 자료실의 해시와 비교해 업데이트를 판단한다. 서버에는 보내지 않는다(사용자별 PC 상태일 뿐이다).
// ---------------------------------------------------------------------------------------------

const storageKey = (envId: number) => `addon-manager:installed-hashes:${envId}`

export function loadInstalledHashes(envId: number): Record<string, string> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(storageKey(envId)) ?? '{}')
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, string>) : {}
  } catch {
    return {}
  }
}

export function rememberInstalled(envId: number, folder: string, hash: string) {
  try {
    const all = loadInstalledHashes(envId)
    all[folder.toLowerCase()] = hash
    localStorage.setItem(storageKey(envId), JSON.stringify(all))
  } catch {
    /* 저장소를 쓸 수 없으면 기록을 남기지 않는다. 그러면 .toc 버전으로 판단한다. */
  }
}

export function forgetInstalled(envId: number, folder: string) {
  try {
    const all = loadInstalledHashes(envId)
    delete all[folder.toLowerCase()]
    localStorage.setItem(storageKey(envId), JSON.stringify(all))
  } catch {
    /* 무시 */
  }
}

// ---------------------------------------------------------------------------------------------

/**
 * NOT_INSTALLED    : 이 PC 에 폴더가 없다
 * UP_TO_DATE       : 설치한 내용이 자료실과 같다
 * UPDATE_AVAILABLE : 자료실에 더 새로운(다른) 내용이 있다
 * UNKNOWN          : 폴더는 있지만 같은지 알 수 없다(이 앱으로 설치한 기록도, 비교할 버전도 없음). 다시 설치하면 자료실 내용이 된다.
 */
export type LibraryState = 'NOT_INSTALLED' | 'UP_TO_DATE' | 'UPDATE_AVAILABLE' | 'UNKNOWN'

/**
 * 자료실 항목의 설치 상태.
 * 이 앱으로 설치한 기록(해시)이 있으면 그것과 자료실 해시를 비교한다(내용이 한 글자만 달라도 알아낸다).
 * 기록이 없는 폴더(직접 넣은 것)는 .toc 버전으로 비교한다.
 */
export function libraryState(item: LibraryAddon, local: LocalFolder | undefined, hashes: Record<string, string>): LibraryState {
  if (!local) return 'NOT_INSTALLED'
  const recorded = hashes[item.name.toLowerCase()]
  if (recorded) return recorded === item.contentHash ? 'UP_TO_DATE' : 'UPDATE_AVAILABLE'
  if (item.version && local.version) return item.version === local.version ? 'UP_TO_DATE' : 'UPDATE_AVAILABLE'
  return 'UNKNOWN'
}

// ---------------------------------------------------------------------------------------------

export interface InstallPlan {
  /** 설치할 순서. 필요한 애드온이 먼저, 요청한 애드온이 마지막이다. */
  order: LibraryAddon[]
  /** 자료실에도 이 PC 에도 없는 필요한 애드온 이름 */
  missing: string[]
}

const MAX_DEPTH = 10

/**
 * 요청한 애드온과, 아직 설치돼 있지 않은 필요한 애드온(의존성의 의존성까지)을 설치 순서대로 정한다.
 * 이미 설치된 것은 버전과 상관없이 충족된 것으로 본다. 순환하거나 같은 애드온이 여러 번 나와도 한 번만 담는다.
 */
export function planInstall(item: LibraryAddon, library: LibraryAddon[], installedNames: Set<string>): InstallPlan {
  const byName = new Map(library.map((a) => [a.name.toLowerCase(), a]))
  const order: LibraryAddon[] = []
  const missing: string[] = []
  const visited = new Set<string>()

  const visit = (a: LibraryAddon, depth: number) => {
    const key = a.name.toLowerCase()
    if (visited.has(key)) return
    visited.add(key)
    if (depth < MAX_DEPTH) {
      for (const dep of a.requires) {
        const depKey = dep.toLowerCase()
        if (installedNames.has(depKey)) continue
        const found = byName.get(depKey)
        if (found) visit(found, depth + 1)
        else if (!missing.includes(dep)) missing.push(dep)
      }
    }
    order.push(a)
  }
  visit(item, 0)
  return { order, missing }
}

export interface InstallOutcome {
  /** 설치한 애드온 이름(필요한 것 포함, 설치 순서) */
  installed: string[]
  /** 자료실에도 이 PC 에도 없어 설치하지 못한 필요한 애드온 */
  missing: string[]
}

/**
 * 자료실에서 받아 설치한다. 필요한 애드온을 먼저 설치한다.
 * 받은 zip 은 쓰기 전에 **내용 해시가 목록에 적힌 값과 같은지**, 폴더가 목록의 이름과 같은지 확인한다.
 * 전송 중 깨졌거나 목록과 다른 파일이면 PC 의 폴더를 건드리지 않고 멈춘다.
 */
export async function installFromLibrary(
  envId: number,
  root: FileSystemDirectoryHandle,
  item: LibraryAddon,
  library: LibraryAddon[],
  installedNames: Set<string>,
  onProgress: (message: string) => void,
): Promise<InstallOutcome> {
  const { order, missing } = planInstall(item, library, installedNames)
  for (const addon of order) {
    onProgress(addon === item ? `설치 중: ${addon.name}` : `필요한 애드온 설치 중: ${addon.name}`)
    const prepared = await prepareZip(await api.downloadLibrary(addon.id))
    if (prepared.folders.length !== 1 || prepared.folders[0].toLowerCase() !== addon.name.toLowerCase()) {
      throw new Error(`'${addon.name}' 파일의 폴더가 목록과 다릅니다. 관리자에게 알려 주세요.`)
    }
    if (prepared.hash !== addon.contentHash) {
      throw new Error(`'${addon.name}' 파일 내용이 목록과 다릅니다(전송 오류일 수 있습니다). 잠시 뒤 다시 시도하세요.`)
    }
    const result = await writePrepared(root, prepared)
    rememberInstalled(envId, addon.name, result.hash)
  }
  return { installed: order.map((a) => a.name), missing }
}
