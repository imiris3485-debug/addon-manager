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
