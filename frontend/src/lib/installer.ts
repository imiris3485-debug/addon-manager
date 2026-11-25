import { combineFileHashes, sha256Hex } from './hash'

/**
 * 애드온 설치(파일 단위). 서버가 준 "파일 목록(경로·크기·SHA-256)"을 검사한 뒤, 내 폴더에 이미 있는 같은 파일은 그대로 두고
 * 바뀌었거나 없는 파일만 묶음 스트림으로 내려받아 파일마다 해시를 확인하고 쓴다. 파일 하나만 메모리에 올리므로 애드온이 아무리
 * 커도 설치할 수 있고, 파일이 수천 개여도 요청은 몇 번뿐이며, 업데이트 때는 바뀐 파일만 받는다.
 */

const MAX_FILES = 20_000
/** 서버의 파일 하나 상한과 같다(데스크톱 앱의 파일 상한 300MB 보다 작다) */
const MAX_FILE_BYTES = 250 * 1024 * 1024

/**
 * 설치를 허용하는 파일 확장자. 서버의 AddonZipInspector 와 같은 목록이다.
 * 서버가 이미 검사하지만, 서버가 잘못되거나 침해돼도 사용자의 게임 폴더에 실행 파일이 쓰이지 않도록
 * 받는 쪽에서도 한 번 더 막는다(방어를 겹친다). 목록을 바꿀 때는 서버와 함께 바꾼다.
 */
const ALLOWED_EXTENSIONS = new Set([
  'lua', 'xml', 'toc', 'json', 'yml', 'yaml', 'cfg', 'ini', 'csv', 'tsv',
  'toml', 'conf', 'properties', 'xsd', 'dtd',
  'tga', 'blp', 'png', 'jpg', 'jpeg', 'gif', 'bmp', 'dds', 'svg', 'webp', 'ico',
  'ttf', 'otf', 'ogg', 'mp3', 'wav', 'flac', 'm4a',
  'txt', 'text', 'md', 'markdown', 'textile', 'rst', 'adoc', 'rtf', 'html', 'htm', 'css',
  'lic', 'license', 'diff', 'patch',
])

// 이름 하나에 경로 구분자(/ \)가 들어 있으면 안 된다(폴더 이름, 경로 조각 모두)
const FORBIDDEN_CHARS = /[<>:"|?*\u0000-\u001f\\/]/
const RESERVED_NAME = /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$/i
const HEX64 = /^[0-9a-f]{64}$/

/** 서버가 준 파일 목록의 한 줄 */
export interface ManifestFile {
  /** 애드온 폴더 기준 상대 경로(/ 구분) */
  path: string
  size: number
  sha256: string
}

export interface InstallResult {
  /** 설치한 내용의 해시(목록의 해시와 같다) */
  hash: string
  /** 새로 받은 파일 수 / 이미 같은 내용이라 그대로 둔 파일 수 */
  downloaded: number
  unchanged: number
  /** 받은 바이트 수 */
  bytes: number
}

/** 폴더 이름으로 쓸 수 있는지(경로 문자·Windows 특수 이름 거부) */
function isSafeName(name: string): boolean {
  return !!name && name !== '.' && name !== '..' && name.length <= 100
    && !FORBIDDEN_CHARS.test(name) && !RESERVED_NAME.test(name) && !name.endsWith('.') && !name.endsWith(' ')
}

function checkExtension(path: string) {
  const name = path.slice(path.lastIndexOf('/') + 1)
  const dot = name.lastIndexOf('.')
  if (dot <= 0) return                     // 확장자가 없거나 점으로 시작하는 파일(LICENSE, .pkgmeta)은 허용
  const ext = name.slice(dot + 1).toLowerCase()
  if (!ALLOWED_EXTENSIONS.has(ext)) throw new Error(`허용하지 않는 파일 형식이 들어 있어 설치하지 않았습니다: ${path}`)
}

/**
 * 파일 목록을 쓰기 전에 검사한다. 경로가 폴더 밖으로 나가거나(.. · 절대 경로 · 드라이브), Windows 에서 다른 의미를 갖는 이름이거나,
 * 허용하지 않는 확장자이거나, 같은 경로(대소문자 무시)가 둘이면 아무것도 쓰지 않고 거부한다.
 */
export function validateManifest(folder: string, files: ManifestFile[]) {
  if (!isSafeName(folder)) throw new Error('애드온 폴더 이름이 올바르지 않습니다.')
  if (files.length === 0) throw new Error('파일 목록이 비어 있습니다.')
  if (files.length > MAX_FILES) throw new Error('파일이 너무 많아 설치할 수 없습니다.')
  const seen = new Set<string>()
  for (const f of files) {
    const parts = f.path.split('/')
    if (f.path.length > 240 || f.path.startsWith('/') || !parts.every(isSafeName)) {
      throw new Error(`안전하지 않은 경로가 포함되어 있습니다: ${f.path.slice(0, 80)}`)
    }
    checkExtension(f.path)
    const key = f.path.toLowerCase()
    if (seen.has(key)) throw new Error(`같은 경로가 두 번 들어 있습니다: ${f.path.slice(0, 80)}`)
    seen.add(key)
    if (!Number.isInteger(f.size) || f.size < 0 || f.size > MAX_FILE_BYTES) throw new Error(`파일 크기가 올바르지 않습니다: ${f.path.slice(0, 80)}`)
    if (!HEX64.test(f.sha256)) throw new Error(`파일 해시가 올바르지 않습니다: ${f.path.slice(0, 80)}`)
  }
}

type DirEntries = AsyncIterable<[string, FileSystemHandle]>
const entriesOf = (dir: FileSystemDirectoryHandle) => (dir as unknown as { entries(): DirEntries }).entries()

/** 폴더 안의 모든 파일의 상대 경로(/ 구분). 폴더가 없으면 빈 목록. */
async function listFiles(dir: FileSystemDirectoryHandle, prefix = ''): Promise<string[]> {
  const out: string[] = []
  for await (const [name, handle] of entriesOf(dir)) {
    const path = prefix ? `${prefix}/${name}` : name
    if (handle.kind === 'directory') out.push(...await listFiles(await dir.getDirectoryHandle(name), path))
    else out.push(path)
  }
  return out
}

async function ensureDir(root: FileSystemDirectoryHandle, parts: string[]): Promise<FileSystemDirectoryHandle> {
  let current = root
  for (const part of parts) current = await current.getDirectoryHandle(part, { create: true })
  return current
}

async function openDir(root: FileSystemDirectoryHandle, parts: string[]): Promise<FileSystemDirectoryHandle | null> {
  let current = root
  try {
    for (const part of parts) current = await current.getDirectoryHandle(part)
    return current
  } catch (e) {
    if ((e as DOMException).name === 'NotFoundError') return null
    throw e
  }
}

async function readFile(root: FileSystemDirectoryHandle, folder: string, path: string): Promise<Uint8Array> {
  const parts = [folder, ...path.split('/')]
  const dir = await openDir(root, parts.slice(0, -1))
  if (!dir) throw new DOMException('폴더가 없습니다.', 'NotFoundError')
  const file = await (await dir.getFileHandle(parts[parts.length - 1])).getFile()
  return new Uint8Array(await file.arrayBuffer())
}

async function writeFile(root: FileSystemDirectoryHandle, folder: string, path: string, data: Uint8Array, dirCache: Map<string, FileSystemDirectoryHandle>) {
  const parts = [folder, ...path.split('/')]
  const dirParts = parts.slice(0, -1)
  const dirKey = dirParts.join('/')
  let dir = dirCache.get(dirKey)
  if (!dir) {
    dir = await ensureDir(root, dirParts)
    dirCache.set(dirKey, dir)
  }
  const handle = await dir.getFileHandle(parts[parts.length - 1], { create: true })
  const writable = await handle.createWritable()
  await writable.write(data as unknown as BufferSource)
  await writable.close()
}

/** 폴더 하나를 지운다. 이미 없으면 넘어간다. */
export async function removeFolder(root: FileSystemDirectoryHandle, name: string) {
  try {
    await root.removeEntry(name, { recursive: true })
  } catch (e) {
    if ((e as DOMException).name !== 'NotFoundError') throw e
  }
}

const mb = (n: number) => (n / 1024 / 1024).toFixed(n < 10 * 1024 * 1024 ? 1 : 0)

/** 내려받은 내용이 목록과 다르다(변조·전송 오류). 다시 시도해도 같으므로 재시도하지 않는다. */
export class IntegrityError extends Error {}

/**
 * 스트림에서 필요한 만큼씩 읽는다. 도착한 조각은 목록에 쌓아 두었다가 필요한 길이만큼 한 번에 합친다(복사를 줄이려는 것).
 */
class StreamReader {
  private chunks: Uint8Array[] = []
  private length = 0
  private ended = false

  constructor(private readonly reader: ReadableStreamDefaultReader<Uint8Array>) {}

  /** 정확히 n 바이트. 읽기 전에 스트림이 이미 끝났으면 null, 중간에 끝났으면 오류. */
  async read(n: number): Promise<Uint8Array | null> {
    if (n === 0) return new Uint8Array(0)
    while (this.length < n && !this.ended) {
      const { value, done } = await this.reader.read()
      if (done) this.ended = true
      else if (value && value.byteLength > 0) {
        this.chunks.push(value)
        this.length += value.byteLength
      }
    }
    if (this.length === 0 && this.ended) return null
    if (this.length < n) throw new Error('받던 중 연결이 끊겼습니다. 다시 시도하세요.')
    const out = new Uint8Array(n)
    let offset = 0
    while (offset < n) {
      const c = this.chunks[0]
      const take = Math.min(c.byteLength, n - offset)
      out.set(c.subarray(0, take), offset)
      offset += take
      if (take === c.byteLength) this.chunks.shift()
      else this.chunks[0] = c.subarray(take)
    }
    this.length -= n
    return out
  }

  async readExact(n: number): Promise<Uint8Array> {
    const data = await this.read(n)
    if (!data) throw new Error('받던 중 연결이 끊겼습니다. 다시 시도하세요.')
    return data
  }
}

/**
 * 파일 묶음 스트림을 읽으면서 파일 하나씩 확인해 넘긴다.
 * 묶음 형식: 파일마다 [경로 길이 2바이트][경로 UTF-8][크기 8바이트][내용](big-endian). 파일이 수천 개여도 요청 한 번으로 받는다.
 * 요청하지 않은 경로, 목록과 다른 크기, 같은 파일의 중복은 거부한다. 파일 하나만 메모리에 올린다.
 */
async function receiveBundle(
  stream: ReadableStream<Uint8Array>,
  expected: Map<string, ManifestFile>,
  onFile: (file: ManifestFile, data: Uint8Array) => Promise<void>,
): Promise<void> {
  const reader = new StreamReader(stream.getReader())
  const decoder = new TextDecoder()
  const seen = new Set<string>()
  for (;;) {
    const head = await reader.read(2)
    if (!head) break
    const path = decoder.decode(await reader.readExact((head[0] << 8) | head[1]))
    const sizeBytes = await reader.readExact(8)
    const size = Number(new DataView(sizeBytes.buffer, sizeBytes.byteOffset, 8).getBigUint64(0))
    const spec = expected.get(path)
    if (!spec) throw new IntegrityError(`요청하지 않은 파일이 왔습니다: ${path.slice(0, 80)}`)
    if (seen.has(path)) throw new IntegrityError(`같은 파일이 두 번 왔습니다: ${path.slice(0, 80)}`)
    if (size !== spec.size) throw new IntegrityError(`'${path}' 의 크기가 목록과 다릅니다(전송 오류일 수 있습니다). 잠시 뒤 다시 시도하세요.`)
    const data = await reader.readExact(size)
    if (await sha256Hex(data) !== spec.sha256) throw new IntegrityError(`'${path}' 의 내용이 목록과 다릅니다(전송 오류일 수 있습니다). 잠시 뒤 다시 시도하세요.`)
    await onFile(spec, data)
    seen.add(path)
  }
  const missing = [...expected.keys()].filter((p) => !seen.has(p))
  if (missing.length > 0) throw new Error(`받은 묶음에 빠진 파일이 ${missing.length}개 있습니다. 다시 시도하세요.`)
}

/** 요청 하나로 받을 파일 수·크기의 상한. 이 단위로 나눠 받으면 중간에 끊겨도 받은 만큼은 남는다. */
const REQUEST_FILES = 5000
const REQUEST_BYTES = 256 * 1024 * 1024
const RETRIES = 2

/**
 * 애드온 하나를 설치·갱신한다.
 *
 * 1. 파일 목록을 검사하고, 목록의 해시가 자료실이 알려 준 내용 해시와 같은지 확인한다(어긋나면 아무것도 쓰지 않는다).
 * 2. 내 폴더의 같은 경로 파일이 크기와 SHA-256 까지 같으면 그대로 둔다.
 * 3. 나머지는 묶음 스트림으로 받는다. 요청 한 번에 여러 파일이 오고(수천 개여도 요청은 몇 번뿐), 파일마다 크기와 SHA-256 을
 *    확인한 뒤에만 쓴다(확인에 실패한 파일은 쓰지 않는다). 네트워크 오류는 받은 파일을 뺀 나머지만 다시 요청한다.
 * 4. 목록에 없는 옛 파일은 지운다(이전 버전에만 있던 파일이 남지 않게). 설정(SavedVariables)은 AddOns 밖(WTF)이라 영향이 없다.
 *
 * ⚠️ 도중에 실패하면 폴더가 일부만 새 내용일 수 있다. 다시 설치하면 이미 받은 파일은 건너뛰고 이어서 받는다.
 *
 * @param fetchBundle 요청한 경로들의 파일을 묶음 스트림으로 내려받는다
 */
export async function installAddonFiles(
  root: FileSystemDirectoryHandle,
  folder: string,
  files: ManifestFile[],
  expectedHash: string,
  fetchBundle: (paths: string[]) => Promise<ReadableStream<Uint8Array>>,
  onProgress: (message: string) => void = () => undefined,
): Promise<InstallResult> {
  validateManifest(folder, files)
  const combined = await combineFileHashes(files.map((f): [string, string] => [`${folder}/${f.path}`, f.sha256]))
  if (combined !== expectedHash.toLowerCase()) {
    throw new Error(`'${folder}' 의 파일 목록이 자료실 정보와 다릅니다. 관리자가 다시 동기화해야 할 수 있습니다.`)
  }

  // 내 폴더에 이미 있는 파일 중 목록과 같은 것은 건너뛴다
  const existingDir = await openDir(root, [folder])
  const local = existingDir ? await listFiles(existingDir) : []
  const localByLower = new Map(local.map((p) => [p.toLowerCase(), p]))
  const need: ManifestFile[] = []
  let unchanged = 0
  for (const [i, f] of files.entries()) {
    const existing = localByLower.get(f.path.toLowerCase())
    if (existing) {
      if (i % 50 === 0) onProgress(`${folder}: 내 폴더와 비교하는 중 (${i + 1}/${files.length})`)
      const data = await readFile(root, folder, existing)
      if (data.byteLength === f.size && await sha256Hex(data) === f.sha256) {
        unchanged++
        continue
      }
    }
    need.push(f)
  }

  // 바뀌었거나 없는 파일을 받는다
  const totalBytes = need.reduce((n, f) => n + f.size, 0)
  const written = new Set<string>()
  let doneBytes = 0
  const dirCache = new Map<string, FileSystemDirectoryHandle>()    // 같은 폴더를 파일마다 다시 확인하지 않게 한다
  const save = async (f: ManifestFile, data: Uint8Array) => {
    await writeFile(root, folder, f.path, data, dirCache)
    written.add(f.path)
    doneBytes += f.size
    if (written.size % 20 === 0 || written.size === need.length) {
      onProgress(`${folder}: ${written.size}/${need.length} 파일 받는 중 (${mb(doneBytes)}/${mb(totalBytes)}MB)`)
    }
  }
  for (let i = 0; i < need.length;) {
    const group: ManifestFile[] = []
    let size = 0
    while (i < need.length && group.length < REQUEST_FILES && (group.length === 0 || size + need[i].size <= REQUEST_BYTES)) {
      size += need[i].size
      group.push(need[i++])
    }
    for (let attempt = 0; ; attempt++) {
      const remaining = group.filter((f) => !written.has(f.path))
      if (remaining.length === 0) break
      try {
        await receiveBundle(await fetchBundle(remaining.map((f) => f.path)), new Map(remaining.map((f) => [f.path, f])), save)
        break
      } catch (e) {
        if (e instanceof IntegrityError || attempt >= RETRIES) throw e
        onProgress(`${folder}: 연결 오류로 이어서 다시 받는 중...`)
        await new Promise((resolve) => setTimeout(resolve, 1000 * (attempt + 1)))
      }
    }
  }

  // 목록에 없는 옛 파일 지우기
  const keep = new Set(files.map((f) => f.path.toLowerCase()))
  for (const p of local) {
    if (keep.has(p.toLowerCase())) continue
    const parts = p.split('/')
    const dir = await openDir(root, [folder, ...parts.slice(0, -1)])
    if (dir) await dir.removeEntry(parts[parts.length - 1])
  }

  return { hash: combined, downloaded: need.length, unchanged, bytes: totalBytes }
}
