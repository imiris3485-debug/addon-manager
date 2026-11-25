import { unzipSync } from 'fflate'
import { contentHash } from './hash'
import { firstVersion, isTocFileFor, mergeTocs } from './toc'

const MAX_UNZIPPED_BYTES = 500 * 1024 * 1024   // 압축 폭탄 방지

/**
 * 설치를 허용하는 파일 확장자. 서버의 AddonZipInspector 와 같은 목록이다.
 * 서버가 이미 검사하지만, 서버가 잘못되거나 침해돼도 사용자의 게임 폴더에 실행 파일이 쓰이지 않도록
 * 받는 쪽에서도 한 번 더 막는다(방어를 겹친다). 목록을 바꿀 때는 서버와 함께 바꾼다.
 */
const ALLOWED_EXTENSIONS = new Set([
  'lua', 'xml', 'toc', 'json', 'yml', 'yaml', 'cfg', 'ini', 'csv', 'tsv',
  'tga', 'blp', 'png', 'jpg', 'jpeg', 'gif', 'bmp', 'dds', 'svg', 'webp', 'ico',
  'ttf', 'otf', 'ogg', 'mp3', 'wav', 'flac', 'm4a',
  'txt', 'text', 'md', 'markdown', 'textile', 'rst', 'adoc', 'rtf', 'html', 'htm', 'css',
  'lic', 'license', 'diff', 'patch',
])

/** 풀어 낸 zip: 최상위 폴더들, 파일 내용, 내용 해시. */
export interface PreparedZip {
  folders: string[]
  files: Map<string, Uint8Array>
  hash: string
}

export interface InstallResult {
  folders: string[]
  tocVersion: string | null
  hash: string
}

/** 경로가 폴더 밖으로 나가거나 절대 경로면 거부한다(zip slip 방지). */
function safePath(path: string): string | null {
  const p = path.replace(/\\/g, '/')
  if (!p || p.startsWith('/') || p.includes(':') || p.split('/').some((s) => s === '..')) return null
  return p
}

function checkExtension(path: string) {
  const name = path.slice(path.lastIndexOf('/') + 1)
  const dot = name.lastIndexOf('.')
  if (dot <= 0) return                     // 확장자가 없거나 점으로 시작하는 파일(LICENSE, .pkgmeta)은 허용
  const ext = name.slice(dot + 1).toLowerCase()
  if (!ALLOWED_EXTENSIONS.has(ext)) throw new Error(`허용하지 않는 파일 형식이 들어 있어 설치하지 않았습니다: ${path}`)
}

function extractZip(zip: Uint8Array): { folders: string[]; files: Map<string, Uint8Array> } {
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
    checkExtension(path)
    folders.add(path.slice(0, slash))
    files.set(path, data)
  }
  if (folders.size === 0) throw new Error('압축 파일에 애드온 폴더가 없습니다.')
  return { folders: [...folders], files }
}

/** zip 을 풀고 내용 해시를 계산한다. 디스크에는 아직 아무것도 쓰지 않는다(해시를 확인한 뒤에 쓰려고 나눴다). */
export async function prepareZip(zip: Uint8Array): Promise<PreparedZip> {
  const { folders, files } = extractZip(zip)
  return { folders, files, hash: await contentHash(files) }
}

async function ensureDir(root: FileSystemDirectoryHandle, parts: string[]): Promise<FileSystemDirectoryHandle> {
  let current = root
  for (const part of parts) current = await current.getDirectoryHandle(part, { create: true })
  return current
}

/** 폴더 하나를 지운다. 이미 없으면 넘어간다. */
export async function removeFolder(root: FileSystemDirectoryHandle, name: string) {
  try {
    await root.removeEntry(name, { recursive: true })
  } catch (e) {
    if ((e as DOMException).name !== 'NotFoundError') throw e
  }
}

/**
 * 풀어 둔 애드온을 AddOns 폴더에 쓴다.
 * 기존 폴더는 지운 뒤 새로 쓴다(이전 버전에만 있던 파일이 남지 않게). 설정(SavedVariables)은 AddOns 밖(WTF)이라 영향이 없다.
 * ⚠️ 쓰는 도중 실패하면 폴더가 불완전한 상태로 남을 수 있다. 그 경우 다시 설치하면 된다.
 */
export async function writePrepared(root: FileSystemDirectoryHandle, prepared: PreparedZip): Promise<InstallResult> {
  const { folders, files, hash } = prepared
  for (const folder of folders) await removeFolder(root, folder)

  for (const [path, data] of files) {
    const parts = path.split('/')
    const dir = await ensureDir(root, parts.slice(0, -1))
    const handle = await dir.getFileHandle(parts[parts.length - 1], { create: true })
    const writable = await handle.createWritable()
    await writable.write(data as unknown as BufferSource)
    await writable.close()
  }

  // 새 .toc 의 버전을 읽는다. 스캔(scanner)과 같은 규칙: `Name_Vanilla.toc` 처럼 나뉜 .toc 까지 모두 본다.
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
  return { folders, tocVersion: firstVersion(versions), hash }
}
