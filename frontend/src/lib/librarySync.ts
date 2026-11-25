import { api, ApiError } from './api'
import { combineFileHashes, sha256Hex } from './hash'
import type { ManifestFile } from './installer'
import { scanFolders } from './scanner'
import type { LibraryAddon } from './types'

// 관리자: 자료실 원본 폴더(읽기 전용)를 서버의 자료실과 맞춘다(동기화).
// 폴더 내용의 해시를 계산해 새로 생겼거나 바뀐 애드온만 올리고, 올릴 때도 서버에 이미 있는 같은 파일은 건너뛰고 바뀐 파일만 올린다.
// 파일 하나씩 조각으로 이어 올리므로 애드온이 아무리 커도(파일 하나당 250MB 이하) 올릴 수 있다.

/** 큰 파일의 조각 하나의 크기. 프록시(Cloudflare)의 요청 크기 제한(100MB)보다 훨씬 작게 잡아 느린 연결에서도 끊기지 않게 한다. */
const CHUNK_BYTES = 8 * 1024 * 1024
/** 이보다 작은 파일은 묶음으로 올린다(파일마다 요청하면 수천 개짜리 애드온은 너무 오래 걸린다). */
const SMALL_FILE_BYTES = 2 * 1024 * 1024
/** 묶음 하나의 크기. 서버 상한(32MB)보다 작게 둔다. */
const BUNDLE_BYTES = 16 * 1024 * 1024
const MAX_DEPS = 30                           // 서버 상한(함께 설치할 애드온 지정 개수)과 같다

/** NEW: 서버에 없음, CHANGED: 내용이 다름, DEPS: 내용은 같지만 함께 설치할 애드온 정보만 갱신, SAME: 같음 */
export type SyncKind = 'NEW' | 'CHANGED' | 'DEPS' | 'SAME'

export interface SyncRow {
  name: string
  version: string | null
  hash: string
  kind: SyncKind
  /** 함께 설치할 애드온: .toc 의 필수·선택 의존성 중 원본 폴더에 실제로 있는 것(자기 자신 제외) */
  deps: string[]
  /** 폴더 안의 파일 목록(경로·크기·SHA-256). 올릴 때 서버에 먼저 보낸다. */
  files: ManifestFile[]
  /** 파일 크기의 합(바이트) */
  bytes: number
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

/**
 * 폴더의 파일 목록과 내용 해시(서버가 올라온 파일 목록에서 계산하는 값과 같다). 파일을 하나씩 읽어 해시만 남기므로 메모리를 많이 쓰지 않는다.
 * 파일 경로는 폴더 기준 상대 경로다.
 */
export async function scanFolderFiles(root: FileSystemDirectoryHandle, folder: string): Promise<{ files: ManifestFile[]; hash: string }> {
  const files: ManifestFile[] = []
  for await (const [path, data] of walkFiles(await root.getDirectoryHandle(folder), folder)) {
    files.push({ path: path.slice(folder.length + 1), size: data.byteLength, sha256: await sha256Hex(data) })
  }
  if (files.length === 0) throw new Error(`'${folder}' 폴더에 파일이 없습니다.`)
  files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0))
  return { files, hash: await combineFileHashes(files.map((f): [string, string] => [`${folder}/${f.path}`, f.sha256])) }
}

/** 폴더의 내용 해시. */
export async function hashFolder(root: FileSystemDirectoryHandle, folder: string): Promise<string> {
  return (await scanFolderFiles(root, folder)).hash
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
  const sourceNames = new Map(folders.map((f) => [f.name.toLowerCase(), f.name]))
  const rows: SyncRow[] = []
  for (const [i, f] of folders.entries()) {
    onProgress(`원본 폴더를 읽는 중 (${i + 1}/${folders.length}) ${f.name}`)
    const { files, hash } = await scanFolderFiles(source, f.name)
    const existing = serverByName.get(f.name.toLowerCase())
    // OptionalDeps 에는 라이브러리(Ace3 등)가 섞여 있다. 원본 폴더에 애드온으로 같이 있는 것만 묶는다.
    const deps = [...f.requires, ...f.optionalRequires]
      .map((d) => sourceNames.get(d.toLowerCase()))
      .filter((d): d is string => !!d && d.toLowerCase() !== f.name.toLowerCase())
      .filter((d, idx, all) => all.indexOf(d) === idx)
      .slice(0, MAX_DEPS)
    const known = new Set((existing?.requires ?? []).map((r) => r.toLowerCase()))
    const kind: SyncKind = !existing ? 'NEW'
      : existing.contentHash !== hash ? 'CHANGED'
      : deps.some((d) => !known.has(d.toLowerCase())) ? 'DEPS' : 'SAME'
    rows.push({ name: f.name, version: f.version, hash, kind, deps, files, bytes: files.reduce((n, x) => n + x.size, 0) })
  }
  const present = new Set(folders.map((f) => f.name.toLowerCase()))
  return { rows, removed: server.filter((s) => !present.has(s.name.toLowerCase())) }
}

/** 이 행 아래에 묶을 행의 이름: 필수·선택 의존성 + 이름이 `이름_` 로 시작하는 모듈(같은 묶음에 딸린 폴더). */
export function childrenOf(row: SyncRow, rows: SyncRow[]): string[] {
  const prefix = row.name.toLowerCase() + '_'
  const modules = rows.filter((r) => r.name.toLowerCase().startsWith(prefix)).map((r) => r.name)
  return [...new Set([...row.deps, ...modules])]
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))
const mb = (n: number) => (n / 1024 / 1024).toFixed(n < 10 * 1024 * 1024 ? 1 : 0)

/** 원본 폴더 안의 파일 하나를 읽는다(폴더 기준 상대 경로). */
async function readSourceFile(root: FileSystemDirectoryHandle, folder: string, path: string): Promise<Uint8Array> {
  let dir = await root.getDirectoryHandle(folder)
  const parts = path.split('/')
  for (const part of parts.slice(0, -1)) dir = await dir.getDirectoryHandle(part)
  const file = await (await dir.getFileHandle(parts[parts.length - 1])).getFile()
  return new Uint8Array(await file.arrayBuffer())
}

/** 서버가 거부한 오류(4xx)는 다시 해도 같으므로 바로 알리고, 네트워크·서버 일시 오류만 몇 번 다시 시도한다. */
async function withRetry<T>(fn: () => Promise<T>): Promise<T> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await fn()
    } catch (e) {
      if (e instanceof ApiError && e.status >= 400 && e.status < 500) throw e
      if (attempt > 3) throw e
      await sleep(1000 * attempt)
    }
  }
}

/** 큰 파일 하나를 조각으로 이어 올린다. 서버가 "다음 위치는 N" 이라고 하면(응답이 유실된 경우) 그 위치부터 이어 올린다. */
async function uploadLargeFile(uploadId: string, path: string, data: Uint8Array, onSent: (bytes: number) => void) {
  let offset = 0
  while (offset < data.byteLength) {
    const end = Math.min(offset + CHUNK_BYTES, data.byteLength)
    try {
      await withRetry(() => api.adminUploadChunk(uploadId, path, offset, new Blob([data.subarray(offset, end) as unknown as BlobPart])))
      onSent(end - offset)
      offset = end
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        const next = Number(/(\d+)/.exec(e.message)?.[1])
        if (Number.isInteger(next) && next > offset && next <= data.byteLength) {
          onSent(next - offset)
          offset = next
          continue
        }
      }
      throw e
    }
  }
}

/** 묶음 형식: 파일마다 [경로 길이 2바이트][경로 UTF-8][크기 8바이트][내용](big-endian). 서버 LibraryUploadService.bundle 과 같다. */
function packBundle(entries: { path: string; data: Uint8Array }[]): Blob {
  const encoder = new TextEncoder()
  const parts: BlobPart[] = []
  for (const e of entries) {
    const path = encoder.encode(e.path)
    const head = new Uint8Array(2 + path.length + 8)
    head[0] = path.length >> 8
    head[1] = path.length & 0xff
    head.set(path, 2)
    new DataView(head.buffer).setBigUint64(2 + path.length, BigInt(e.data.byteLength))
    parts.push(head, e.data as unknown as BlobPart)
  }
  return new Blob(parts)
}

/**
 * 애드온 폴더 하나를 서버 자료실에 올린다. 같은 이름이면 새 내용으로 바뀐다.
 * 파일 목록을 먼저 보내 서버가 "올려야 할 파일"만 알려 주면, 그 파일만 올린다(이미 서버에 있는 같은 파일은 건너뜀).
 * 작은 파일은 묶음으로 한꺼번에 올리고(요청 수는 파일 수가 아니라 용량에 비례), 큰 파일만 조각으로 이어 올린다.
 */
export async function uploadFolder(
  source: FileSystemDirectoryHandle,
  row: SyncRow,
  note: string,
  onProgress: (message: string) => void,
): Promise<LibraryAddon> {
  const begin = await api.adminUploadBegin(row.name, note, row.files)
  try {
    const sizeByPath = new Map(row.files.map((f) => [f.path, f.size]))
    const totalBytes = begin.needed.reduce((n, p) => n + (sizeByPath.get(p) ?? 0), 0)
    let sentBytes = 0
    let sentFiles = 0
    const report = () => onProgress(`올리는 중 ${row.name}: ${sentFiles}/${begin.needed.length} 파일 (${mb(sentBytes)}/${mb(totalBytes)}MB)`)

    const small = begin.needed.filter((p) => (sizeByPath.get(p) ?? 0) <= SMALL_FILE_BYTES)
    const large = begin.needed.filter((p) => (sizeByPath.get(p) ?? 0) > SMALL_FILE_BYTES)
    const read = async (path: string) => {
      const data = await readSourceFile(source, row.name, path)       // 파일 하나(또는 한 묶음)만 메모리에 올린다
      if (data.byteLength !== sizeByPath.get(path)) throw new Error(`'${path}' 파일이 읽는 사이에 바뀌었습니다. 동기화 계획을 다시 만들어 주세요.`)
      return data
    }

    // 작은 파일: 묶음 하나가 BUNDLE_BYTES 를 넘지 않게 모아서 올린다
    let batch: { path: string; data: Uint8Array }[] = []
    let batchBytes = 0
    const flush = async () => {
      if (batch.length === 0) return
      const blob = packBundle(batch)
      await withRetry(() => api.adminUploadBundle(begin.uploadId, blob))
      sentFiles += batch.length
      sentBytes += batchBytes
      batch = []
      batchBytes = 0
      report()
    }
    for (const path of small) {
      const data = await read(path)
      if (batchBytes + data.byteLength > BUNDLE_BYTES) await flush()
      batch.push({ path, data })
      batchBytes += data.byteLength
    }
    await flush()

    // 큰 파일: 파일 하나씩 조각으로 이어 올린다
    for (const path of large) {
      await uploadLargeFile(begin.uploadId, path, await read(path), (bytes) => {
        sentBytes += bytes
        report()
      })
      sentFiles++
      report()
    }
    return await api.adminUploadComplete(begin.uploadId)
  } catch (e) {
    await api.adminUploadAbort(begin.uploadId).catch(() => undefined)       // 올리다 만 임시 파일은 서버에서 치운다
    throw e
  }
}

/**
 * 감지한 함께 설치할 애드온을 서버 항목에 합친다. 관리자가 직접 지정한 값은 지우지 않고 더하기만 한다.
 * (.toc 가 이미 요구하는 것은 서버가 따로 합치므로 중복해서 넣지 않는다.) 바뀐 게 없으면 서버를 부르지 않는다.
 */
export async function applyDeps(item: LibraryAddon, detected: string[]): Promise<LibraryAddon> {
  const manual = item.extraRequires
  const fromToc = new Set(item.requires.filter((r) => !manual.includes(r)).map((r) => r.toLowerCase()))
  const have = new Set(manual.map((r) => r.toLowerCase()))
  const next = [...manual]
  for (const d of detected) {
    if (fromToc.has(d.toLowerCase()) || have.has(d.toLowerCase())) continue
    next.push(d)
    have.add(d.toLowerCase())
  }
  if (next.length === manual.length) return item
  return api.adminSetLibraryRequires(item.id, next)
}
