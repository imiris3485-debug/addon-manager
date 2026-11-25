import { api } from './api'
import { prepareZip, writePrepared } from './installer'
import type { InstallEvent, LibraryAddon, LocalFolder } from './types'

// ---------------------------------------------------------------------------------------------
// 설치한 내용의 기록. 이 앱이 자료실에서 설치할 때 폴더 이름 → 내용 해시를 이 PC(브라우저/앱)에 남겨 두고,
// 자료실의 해시와 비교해 업데이트를 판단한다. 판단은 이 기록과 PC 폴더로 하고, 서버에는 '무엇을 설치·삭제했는지'만
// 따로 알려 계정별 현황·이력으로 쌓는다(세팅 공유용. 알리기에 실패해도 설치는 그대로 성공이다).
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
 * 요청한 애드온과, 아직 충족되지 않은 필요한 애드온(의존성의 의존성까지)을 설치 순서대로 정한다.
 * 충족된 것(satisfied) = 이미 설치돼 있고 자료실과 같은(또는 판단할 수 없어 건드리지 않기로 한) 것.
 * 설치돼 있어도 자료실에 더 새 내용이 있으면 충족된 것이 아니라서 함께 업데이트한다.
 * 순환하거나 같은 애드온이 여러 번 나와도 한 번만 담는다.
 */
export function planInstall(item: LibraryAddon, library: LibraryAddon[], satisfiedNames: Set<string>): InstallPlan {
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
        if (satisfiedNames.has(depKey)) continue
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

/** 설치·삭제 결과를 서버에 알린다. 실패해도 PC 의 설치 결과에는 영향이 없으므로 조용히 넘긴다. */
export async function reportInstalls(envId: number, events: InstallEvent[]): Promise<void> {
  if (events.length === 0) return
  try {
    for (let i = 0; i < events.length; i += 50) await api.recordInstalls(envId, events.slice(i, i + 50))
  } catch {
    /* 기록은 안내용이다 */
  }
}

/**
 * 이미 충족된 애드온 이름(소문자). 설치돼 있으면서 자료실보다 오래된 것(UPDATE_AVAILABLE)은 뺀다.
 * 이걸 planInstall 에 넘기면 애드온을 설치·업데이트할 때 오래된 필요한 애드온도 함께 최신으로 올라간다.
 */
export function satisfiedNames(library: LibraryAddon[], local: LocalFolder[], hashes: Record<string, string>): Set<string> {
  const byName = new Map(library.map((a) => [a.name.toLowerCase(), a]))
  const out = new Set<string>()
  for (const l of local) {
    const item = byName.get(l.name.toLowerCase())
    // 자료실에 없는 폴더는 건드릴 수 없으니 충족된 것으로 본다
    if (!item || libraryState(item, l, hashes) !== 'UPDATE_AVAILABLE') out.add(l.name.toLowerCase())
  }
  return out
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
  satisfiedNames: Set<string>,
  onProgress: (message: string) => void,
): Promise<InstallOutcome> {
  const { order, missing } = planInstall(item, library, satisfiedNames)
  const events: InstallEvent[] = []
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
    events.push({ addonName: addon.name, action: 'INSTALLED', contentHash: result.hash })   // 처음인지 업데이트인지는 서버가 기록으로 가린다
  }
  await reportInstalls(envId, events)
  return { installed: order.map((a) => a.name), missing }
}

// ---------------------------------------------------------------------------------------------

export interface SetupApplyOutcome {
  /** 설치·업데이트한 애드온(필요한 것 포함) */
  installed: string[]
  /** 이미 최신이거나 직접 넣은 폴더라 건드리지 않은 애드온 */
  skipped: string[]
  /** 자료실에도 이 PC 에도 없는 필요한 애드온 */
  missing: string[]
  /** 설치하다 실패한 애드온과 이유 */
  failed: { name: string; reason: string }[]
}

/**
 * 세팅대로 설치한다. 설치 안 한 것과 업데이트가 있는 것만 설치하고, 이미 최신이거나 직접 넣은 폴더(UNKNOWN)는 건드리지 않는다.
 * 하나가 실패해도 나머지를 계속하고 결과를 모아서 알린다.
 */
export async function applySetup(
  envId: number,
  root: FileSystemDirectoryHandle,
  addonNames: string[],
  library: LibraryAddon[],
  local: LocalFolder[],
  hashes: Record<string, string>,
  onProgress: (message: string) => void,
): Promise<SetupApplyOutcome> {
  const byName = new Map(library.map((a) => [a.name.toLowerCase(), a]))
  const localByName = new Map(local.map((l) => [l.name.toLowerCase(), l]))
  const satisfied = satisfiedNames(library, local, hashes)
  const out: SetupApplyOutcome = { installed: [], skipped: [], missing: [], failed: [] }

  for (const [i, name] of addonNames.entries()) {
    const item = byName.get(name.toLowerCase())
    if (!item) { out.missing.push(name); continue }
    const state = libraryState(item, localByName.get(item.name.toLowerCase()), hashes)
    if (state === 'UP_TO_DATE' || state === 'UNKNOWN') { out.skipped.push(item.name); continue }
    // 앞에서 의존성으로 이미 설치됐을 수 있다
    if (out.installed.includes(item.name)) continue
    onProgress(`(${i + 1}/${addonNames.length}) ${item.name}`)
    try {
      const result = await installFromLibrary(envId, root, item, library, satisfied, () => undefined)
      for (const n of result.installed) { out.installed.push(n); satisfied.add(n.toLowerCase()) }
      for (const m of result.missing) if (!out.missing.includes(m)) out.missing.push(m)
    } catch (e) {
      out.failed.push({ name: item.name, reason: (e as Error).message })
    }
  }
  return out
}
