import { unzipSync } from 'fflate'
import { api } from './api'
import { invalidateFingerprints } from './scanner'
import { firstVersion, parseToc } from './toc'

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
  const versions = []
  for (const folder of folders) {
    const toc = files.get(`${folder}/${folder}.toc`)
    versions.push({ folderName: folder, tocVersion: toc ? parseToc(new TextDecoder().decode(toc)).version : null })
  }
  const tocVersion = firstVersion(versions)

  await api.reportInstalled(envId, { projectId, fileId, tocVersion, folders })
  return { folders, tocVersion }
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
