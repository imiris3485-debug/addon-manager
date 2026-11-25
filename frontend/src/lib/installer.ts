import { unzipSync } from 'fflate'
import { api } from './api'
import { invalidateFingerprints } from './scanner'
import type { DependencyInfo } from './types'
import { firstVersion, isTocFileFor, mergeTocs } from './toc'

const MAX_UNZIPPED_BYTES = 500 * 1024 * 1024   // 압축 폭탄 방지

interface ExtractedZip {
  folders: string[]
  files: Map<string, Uint8Array>
}

/** 경로가 폴더 밖으로 나가거나 절대 경로면 거부한다(zip slip 방지). */
function safePath(path: string): string | null {
  const p = path.replace(/\\/g, '/')
  if (!p || p.startsWith('/') || p.includes(':') || p.split('/').some((s) => s === '..')) return null
  return p
}

function extractZip(zip: Uint8Array): ExtractedZip {
  let total = 0
  const raw = unzipSync(zip, {
    filter: (f) => {
      total += f.originalSize
      if (total > MAX_UNZIPPED_BYTES) throw new Error('압축 해제 크기가 너무 큽니다.')
      return true
    },
  })

  const files = new Map<string, Uint8Array>()
  const folders = new Set<string>()
  for (const [name, data] of Object.entries(raw)) {
    if (name.endsWith('/')) continue                   // 디렉터리 항목
    const path = safePath(name)
    if (!path) throw new Error(`안전하지 않은 경로가 포함되어 있습니다: ${name}`)
    const slash = path.indexOf('/')
    if (slash < 0) throw new Error('압축 파일 최상위에 폴더가 아닌 파일이 있어 설치할 수 없습니다.')
    folders.add(path.slice(0, slash))
    files.set(path, data)
  }
  if (folders.size === 0) throw new Error('압축 파일에 애드온 폴더가 없습니다.')
  return { folders: [...folders], files }
}

async function ensureDir(root: FileSystemDirectoryHandle, parts: string[]): Promise<FileSystemDirectoryHandle> {
  let current = root
  for (const part of parts) current = await current.getDirectoryHandle(part, { create: true })
  return current
}

async function removeFolder(root: FileSystemDirectoryHandle, name: string) {
  try {
    await root.removeEntry(name, { recursive: true })
  } catch (e) {
    if ((e as DOMException).name !== 'NotFoundError') throw e
  }
}

export interface InstallResult {
  folders: string[]
  tocVersion: string | null
}

/**
 * 파일을 받아 AddOns 폴더에 설치한다.
 * 기존 폴더는 지운 뒤 새로 쓴다(이전 버전에만 있던 파일이 남지 않게). 설정(SavedVariables)은 AddOns 밖(WTF)이라 영향이 없다.
 * ⚠️ 쓰는 도중 실패하면 폴더가 불완전한 상태로 남을 수 있다. 그 경우 다시 설치하면 된다.
 */
export async function installAddon(
  envId: number,
  root: FileSystemDirectoryHandle,
  projectId: number,
  fileId: number,
): Promise<InstallResult> {
  const zip = await api.download(projectId, fileId)
  const { folders, files } = extractZip(zip)

  invalidateFingerprints(envId, folders)
  for (const folder of folders) await removeFolder(root, folder)

  for (const [path, data] of files) {
    const parts = path.split('/')
    const dir = await ensureDir(root, parts.slice(0, -1))
    const handle = await dir.getFileHandle(parts[parts.length - 1], { create: true })
    const writable = await handle.createWritable()
    await writable.write(data as unknown as BufferSource)
    await writable.close()
  }

  // 새 .toc 의 버전을 읽어 서버 기록에 남긴다. 다음 스캔에서 이 버전이 같으면 기록한 파일 ID 를 신뢰한다.
  // 스캔(scanner)과 같은 규칙으로 읽는다: `Name_Vanilla.toc` 처럼 나뉜 .toc 까지 모두 본다.
  const decoder = new TextDecoder()
  const versions = folders.map((folder) => {
    const texts: { fileName: string; text: string }[] = []
    for (const [path, data] of files) {
      const slash = path.indexOf('/')
      const fileName = path.slice(slash + 1)
      if (path.slice(0, slash) === folder && !fileName.includes('/') && isTocFileFor(folder, fileName)) {
        texts.push({ fileName, text: decoder.decode(data) })
      }
    }
    return { folderName: folder, tocVersion: mergeTocs(texts).version }
  })
  const tocVersion = firstVersion(versions)

  await api.reportInstalled(envId, { projectId, fileId, tocVersion, folders })
  return { folders, tocVersion }
}

export interface DependencyOutcome {
  /** 함께 설치한 필수 애드온 이름 */
  installed: string[]
  /** 설치하지 못한 필수 애드온과 이유 */
  skipped: { name: string; reason: string }[]
}

export interface InstallWithDependenciesResult {
  result: InstallResult
  dependencies: DependencyOutcome
}

/**
 * 필수 의존성을 먼저 설치한 뒤 요청한 애드온을 설치한다(CurseForge 앱과 같은 동작).
 * - 이미 설치된 것은 건너뛴다(installedIds).
 * - 의존성을 설치하지 못해도 요청한 애드온은 설치하고, 못 한 것과 이유를 돌려줘 화면에 알린다.
 * - 의존성은 서버가 가까운 것부터 주므로, 설치는 뒤에서부터 해서 가장 안쪽 의존성을 먼저 깐다.
 */
export async function installWithDependencies(
  envId: number,
  root: FileSystemDirectoryHandle,
  projectId: number,
  fileId: number,
  installedIds: Set<number>,
  onProgress: (message: string) => void,
): Promise<InstallWithDependenciesResult> {
  const outcome: DependencyOutcome = { installed: [], skipped: [] }

  let dependencies: DependencyInfo[] = []
  try {
    onProgress('필수 애드온을 확인하는 중...')
    dependencies = await api.dependencies(envId, projectId, fileId)
  } catch {
    outcome.skipped.push({ name: '필수 애드온 확인', reason: '조회하지 못했습니다. 필요한 애드온이 빠졌을 수 있습니다.' })
  }

  const known = new Set(installedIds)
  for (const dep of [...dependencies].reverse()) {
    if (known.has(dep.projectId)) continue
    if (dep.latestFileId == null) {
      outcome.skipped.push({ name: dep.name, reason: '이 게임 버전용 파일이 없습니다' })
      continue
    }
    if (!dep.downloadable) {
      outcome.skipped.push({ name: dep.name, reason: '작성자가 외부 배포를 막아 CurseForge 에서 직접 받아야 합니다' })
      continue
    }
    onProgress(`필수 애드온 설치 중: ${dep.name}`)
    try {
      await installAddon(envId, root, dep.projectId, dep.latestFileId)
      outcome.installed.push(dep.name)
      known.add(dep.projectId)
    } catch (e) {
      outcome.skipped.push({ name: dep.name, reason: (e as Error).message })
    }
  }

  onProgress('설치 중...')
  const result = await installAddon(envId, root, projectId, fileId)
  return { result, dependencies: outcome }
}

/** 화면에 보여 줄 한 줄 요약. 함께 설치한 것과 못 한 것을 알린다. */
export function describeDependencies(outcome: DependencyOutcome): string[] {
  const lines: string[] = []
  if (outcome.installed.length > 0) lines.push(`필수 애드온도 함께 설치했습니다: ${outcome.installed.join(', ')}`)
  for (const s of outcome.skipped) lines.push(`⚠ ${s.name}: ${s.reason}`)
  return lines
}

/**
 * CurseForge 와 연결되지 않은 폴더(남은 라이브러리, 다른 경로로 설치한 것 등)를 지운다.
 * 연결된 애드온과 달리 서버의 설치 기록이 없으므로 폴더만 지운다. 이름은 스캔이 알려 준 AddOns 안의 폴더뿐이고,
 * 실제 삭제 범위는 앱(main)이 AddOns 폴더 안으로 다시 제한한다.
 */
export async function removeUnmanagedFolder(envId: number, root: FileSystemDirectoryHandle, folder: string) {
  invalidateFingerprints(envId, [folder])
  await removeFolder(root, folder)
}

export async function removeAddon(
  envId: number,
  root: FileSystemDirectoryHandle,
  projectId: number,
  folders: string[],
) {
  invalidateFingerprints(envId, folders)
  for (const folder of folders) await removeFolder(root, folder)
  await api.removeInstalled(envId, projectId)
}
