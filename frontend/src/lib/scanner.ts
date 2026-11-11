import { api } from './api'
import { computeFolderFingerprint, readFileAt } from './fingerprint'
import { parseToc } from './toc'
import type { ScanEntry, ScanResponse } from './types'

// 폴더 지문 계산은 파일을 많이 읽어 느리므로 폴더별로 기억해 둔다. 설치/삭제 시 invalidate 한다.
const fingerprintCache = new Map<string, number | null>()
const cacheKey = (envId: number, folder: string) => `${envId}:${folder.toLowerCase()}`

export function invalidateFingerprints(envId: number, folders: string[]) {
  folders.forEach((f) => fingerprintCache.delete(cacheKey(envId, f)))
}

async function listDirectories(root: FileSystemDirectoryHandle): Promise<string[]> {
  const names: string[] = []
  const entries = (root as unknown as { entries(): AsyncIterable<[string, FileSystemHandle]> }).entries()
  for await (const [name, handle] of entries) {
    if (handle.kind === 'directory' && !name.startsWith('.')) names.push(name)
  }
  return names
}

/** 1단계: 각 폴더의 .toc 만 읽는다(빠름). */
async function scanTocs(root: FileSystemDirectoryHandle): Promise<ScanEntry[]> {
  const entries: ScanEntry[] = []
  for (const name of await listDirectories(root)) {
    const dir = await root.getDirectoryHandle(name)
    const data = await readFileAt(dir, `${name}.toc`)
    if (!data) continue                       // .toc 가 없으면 애드온 폴더로 보지 않는다
    const toc = parseToc(new TextDecoder().decode(data))
    entries.push({ folderName: name, tocVersion: toc.version, projectId: toc.projectId, fingerprint: null })
  }
  return entries
}

export interface ScanProgress {
  (message: string): void
}

/**
 * 폴더를 스캔해 서버에서 판정을 받는다.
 * 설치된 파일을 모르는 애드온과 매칭되지 않은 폴더만 2단계로 지문을 계산해 다시 판정을 요청한다.
 */
export async function scanEnvironment(
  envId: number,
  root: FileSystemDirectoryHandle,
  progress: ScanProgress,
): Promise<ScanResponse> {
  progress('폴더 목록을 읽는 중...')
  const entries = await scanTocs(root)
  entries.forEach((e) => {
    const cached = fingerprintCache.get(cacheKey(envId, e.folderName))
    if (cached != null) e.fingerprint = cached
  })

  progress('업데이트 여부를 확인하는 중...')
  let result = await api.scan(envId, entries)

  const need = new Set<string>(result.unmatchedFolders)
  result.addons.filter((a) => a.status === 'UNKNOWN').forEach((a) => a.folders.forEach((f) => need.add(f)))
  const targets = entries.filter((e) => need.has(e.folderName) && e.fingerprint == null
    && !fingerprintCache.has(cacheKey(envId, e.folderName)))

  if (targets.length > 0) {
    for (const [i, e] of targets.entries()) {
      progress(`설치된 버전을 확인하는 중... (${i + 1}/${targets.length})`)
      const dir = await root.getDirectoryHandle(e.folderName)
      e.fingerprint = await computeFolderFingerprint(dir, e.folderName)
      fingerprintCache.set(cacheKey(envId, e.folderName), e.fingerprint)
    }
    progress('다시 판정하는 중...')
    result = await api.scan(envId, entries)
  }
  return result
}
