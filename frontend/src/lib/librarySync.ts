import { zipSync } from 'fflate'
import { api } from './api'
import { combineFileHashes, sha256Hex } from './hash'
import { scanFolders } from './scanner'
import type { LibraryAddon } from './types'

// 관리자: 자료실 원본 폴더(읽기 전용)를 서버의 자료실과 맞춘다(동기화).
// 폴더 내용의 해시를 계산해 새로 생겼거나 바뀐 애드온만 올리므로, 하루에 한 번 돌려도 바뀐 것만 올라간다.

const MAX_ZIP_BYTES = 90 * 1024 * 1024        // 서버 상한과 같다
const MAX_READ_BYTES = 400 * 1024 * 1024

/** NEW: 서버에 없음, CHANGED: 내용이 다름, SAME: 같음 */
export type SyncKind = 'NEW' | 'CHANGED' | 'SAME'

export interface SyncRow {
  name: string
  version: string | null
  hash: string
  kind: SyncKind
}

export interface SyncPlan {
  rows: SyncRow[]
  /** 원본 폴더에는 없지만 서버 자료실에 남아 있는 항목(원본에서 지운 애드온) */
  removed: LibraryAddon[]
}

type DirEntries = AsyncIterable<[string, FileSystemHandle]>
const entriesOf = (dir: FileSystemDirectoryHandle) => (dir as unknown as { entries(): DirEntries }).entries()

/** 폴더 안의 모든 파일을 [상대 경로, 내용] 으로 돌려준다. 경로는 `폴더/하위/파일` 형태다. */
async function* walkFiles(dir: FileSystemDirectoryHandle, prefix: string): AsyncGenerator<[string, Uint8Array]> {
  for await (const [name, handle] of entriesOf(dir)) {
    const path = `${prefix}/${name}`
    if (handle.kind === 'directory') {
      yield* walkFiles(await dir.getDirectoryHandle(name), path)
    } else {
      const file = await (await dir.getFileHandle(name)).getFile()
      yield [path, new Uint8Array(await file.arrayBuffer())]
    }
  }
}

/** 폴더의 내용 해시(서버가 올라온 zip 에서 계산하는 값과 같다). 파일을 하나씩 읽어 해시만 남기므로 메모리를 많이 쓰지 않는다. */
export async function hashFolder(root: FileSystemDirectoryHandle, folder: string): Promise<string> {
  const pairs: [string, string][] = []
  for await (const [path, data] of walkFiles(await root.getDirectoryHandle(folder), folder)) {
    pairs.push([path, await sha256Hex(data)])
  }
  if (pairs.length === 0) throw new Error(`'${folder}' 폴더에 파일이 없습니다.`)
  return combineFileHashes(pairs)
}

/** 폴더 하나를 zip 으로 묶는다. 모든 파일을 담는다(서버가 실행 파일 등을 다시 검사한다). */
export async function zipFolder(root: FileSystemDirectoryHandle, folder: string): Promise<Uint8Array> {
  const files: Record<string, Uint8Array> = {}
  let total = 0
  for await (const [path, data] of walkFiles(await root.getDirectoryHandle(folder), folder)) {
    total += data.byteLength
    if (total > MAX_READ_BYTES) throw new Error(`'${folder}' 애드온이 너무 큽니다.`)
    files[path] = data
  }
  if (Object.keys(files).length === 0) throw new Error(`'${folder}' 폴더에 파일이 없습니다.`)
  const zip = zipSync(files, { level: 6 })
  if (zip.byteLength > MAX_ZIP_BYTES) throw new Error(`'${folder}' 를 묶은 파일이 너무 큽니다(최대 ${MAX_ZIP_BYTES / 1024 / 1024}MB).`)
  return zip
}

/**
 * 원본 폴더를 읽어 서버 자료실과 비교한 계획을 만든다. 아무것도 올리거나 지우지 않는다.
 * 폴더 이름은 대소문자 구분 없이 같은 것으로 본다(Windows 폴더와 서버의 이름 규칙).
 */
export async function buildSyncPlan(
  source: FileSystemDirectoryHandle,
  server: LibraryAddon[],
  onProgress: (message: string) => void,
): Promise<SyncPlan> {
  const folders = await scanFolders(source)
  const serverByName = new Map(server.map((s) => [s.name.toLowerCase(), s]))
  const rows: SyncRow[] = []
  for (const [i, f] of folders.entries()) {
    onProgress(`원본 폴더를 읽는 중 (${i + 1}/${folders.length}) ${f.name}`)
    const hash = await hashFolder(source, f.name)
    const existing = serverByName.get(f.name.toLowerCase())
    rows.push({ name: f.name, version: f.version, hash, kind: !existing ? 'NEW' : existing.contentHash === hash ? 'SAME' : 'CHANGED' })
  }
  const present = new Set(folders.map((f) => f.name.toLowerCase()))
  return { rows, removed: server.filter((s) => !present.has(s.name.toLowerCase())) }
}

/** 폴더 하나를 묶어 서버 자료실에 올린다. 같은 이름이면 새 내용으로 바뀐다. */
export async function uploadFolder(source: FileSystemDirectoryHandle, folder: string, note: string): Promise<LibraryAddon> {
  const zip = await zipFolder(source, folder)
  return api.adminUploadLibrary(note, new Blob([zip as unknown as BlobPart]))
}
