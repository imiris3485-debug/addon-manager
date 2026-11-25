import { readFileAt } from './fileAccess'
import { isTocFileFor, mergeTocs } from './toc'
import type { LocalFolder } from './types'

async function listDirectories(root: FileSystemDirectoryHandle): Promise<string[]> {
  const names: string[] = []
  const entries = (root as unknown as { entries(): AsyncIterable<[string, FileSystemHandle]> }).entries()
  for await (const [name, handle] of entries) {
    if (handle.kind === 'directory' && !name.startsWith('.')) names.push(name)
  }
  return names
}

/** 폴더 안의 이 애드온 .toc 파일 이름들(`Name.toc`, `Name_Vanilla.toc` 등). */
async function listTocFiles(dir: FileSystemDirectoryHandle, folderName: string): Promise<string[]> {
  const names: string[] = []
  const entries = (dir as unknown as { entries(): AsyncIterable<[string, FileSystemHandle]> }).entries()
  for await (const [name, handle] of entries) {
    if (handle.kind === 'file' && isTocFileFor(folderName, name)) names.push(name)
  }
  return names
}

/**
 * 폴더 안의 애드온 폴더(.toc 가 있는 폴더)를 읽는다. 사용자의 AddOns 폴더와 관리자의 자료실 원본 폴더 모두에 쓴다.
 * 클라이언트별로 나뉜 .toc 도 모두 읽어 버전과 필요한 애드온을 찾는다. 서버와 통신하지 않는다.
 */
export async function scanFolders(root: FileSystemDirectoryHandle): Promise<LocalFolder[]> {
  const result: LocalFolder[] = []
  const decoder = new TextDecoder()
  for (const name of await listDirectories(root)) {
    const dir = await root.getDirectoryHandle(name)
    const tocFiles = await listTocFiles(dir, name)
    if (tocFiles.length === 0) continue       // .toc 가 하나도 없으면 애드온 폴더로 보지 않는다
    const texts: { fileName: string; text: string }[] = []
    for (const fileName of tocFiles) {
      const data = await readFileAt(dir, fileName)
      if (data) texts.push({ fileName, text: decoder.decode(data) })
    }
    const { version, requires } = mergeTocs(texts)
    result.push({ name, version, requires })
  }
  return result.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
}
