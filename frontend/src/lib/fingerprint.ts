import { parseToc } from './toc'

/**
 * CurseForge 폴더 지문(fingerprint) 계산.
 * - 파일마다 공백 계열 바이트(탭, 개행, 캐리지리턴, 스페이스)를 제거하고 MurmurHash2(seed=1)를 계산
 * - 해시들을 오름차순 정렬해 문자열로 이어 붙인 뒤 다시 MurmurHash2(seed=1)
 * 대상 파일은 `<폴더>.toc` 와 toc 가 나열한 파일, 그리고 xml 이 Include/Script 로 가져오는 파일이다.
 * 서버가 CurseForge 의 fingerprints API 로 조회하므로, 계산이 어긋나면 "매칭 실패"가 될 뿐 오동작하지는 않는다.
 */

export function murmur2(data: Uint8Array, seed = 1): number {
  const m = 0x5bd1e995
  let length = data.length
  let h = (seed ^ length) >>> 0
  let i = 0
  while (length >= 4) {
    let k = data[i] | (data[i + 1] << 8) | (data[i + 2] << 16) | (data[i + 3] << 24)
    k = Math.imul(k, m)
    k ^= k >>> 24
    k = Math.imul(k, m)
    h = Math.imul(h, m) ^ k
    i += 4
    length -= 4
  }
  // 남은 1~3바이트 처리 (원본 알고리즘의 switch fall-through 와 같은 동작)
  if (length >= 3) h ^= data[i + 2] << 16
  if (length >= 2) h ^= data[i + 1] << 8
  if (length >= 1) {
    h ^= data[i]
    h = Math.imul(h, m)
  }
  h ^= h >>> 13
  h = Math.imul(h, m)
  h ^= h >>> 15
  return h >>> 0
}

function stripWhitespace(data: Uint8Array): Uint8Array {
  const out = new Uint8Array(data.length)
  let n = 0
  for (const b of data) {
    if (b !== 9 && b !== 10 && b !== 13 && b !== 32) out[n++] = b
  }
  return out.subarray(0, n)
}

/** 폴더 핸들 기준 상대 경로의 파일을 읽는다. 없거나 상위 경로(..)가 있으면 null. */
export async function readFileAt(dir: FileSystemDirectoryHandle, relPath: string): Promise<Uint8Array | null> {
  try {
    const parts = relPath.split('/').filter((p) => p && p !== '.')
    if (parts.length === 0 || parts.includes('..')) return null
    let current = dir
    for (const part of parts.slice(0, -1)) current = await current.getDirectoryHandle(part)
    const file = await (await current.getFileHandle(parts[parts.length - 1])).getFile()
    return new Uint8Array(await file.arrayBuffer())
  } catch {
    return null
  }
}

const decoder = new TextDecoder()

function dirname(path: string): string {
  const i = path.lastIndexOf('/')
  return i < 0 ? '' : path.slice(0, i + 1)
}

/** xml 이 가져오는 파일(Include/Script file="...")을 재귀로 모은다. */
async function collectFiles(dir: FileSystemDirectoryHandle, seeds: string[]): Promise<Map<string, Uint8Array>> {
  const found = new Map<string, Uint8Array>()
  const queue = [...seeds]
  while (queue.length) {
    const path = queue.shift()!
    const key = path.toLowerCase()
    if (found.has(key)) continue
    const data = await readFileAt(dir, path)
    if (!data) continue
    found.set(key, data)
    if (key.endsWith('.xml')) {
      const xml = decoder.decode(data)
      for (const m of xml.matchAll(/<(?:Script|Include)\s[^>]*file\s*=\s*"([^"]+)"/gi)) {
        queue.push(dirname(path) + m[1].replace(/\\/g, '/'))
      }
    }
  }
  return found
}

export async function computeFolderFingerprint(
  dir: FileSystemDirectoryHandle,
  folderName: string,
): Promise<number | null> {
  const tocPath = `${folderName}.toc`
  const toc = await readFileAt(dir, tocPath)
  if (!toc) return null

  const files = await collectFiles(dir, [tocPath, ...parseToc(decoder.decode(toc)).files])
  const hashes = [...files.values()].map((data) => murmur2(stripWhitespace(data)))
  hashes.sort((a, b) => a - b)
  return murmur2(new TextEncoder().encode(hashes.join('')))
}
