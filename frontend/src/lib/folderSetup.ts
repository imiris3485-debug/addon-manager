import { readFileAt } from './fileAccess'
import { parseToc } from './toc'

// 브라우저는 보안상 PC 의 폴더 경로를 스스로 찾을 수 없다. 사용자가 한 번 고른 폴더를 해석해서
// 실제 AddOns 폴더를 찾고, 게임 버전을 추정하는 일까지 여기서 처리한다.

/** 일치하는 이름의 하위 폴더를 대소문자 구분 없이 찾는다(Windows 는 구분하지 않지만 다른 OS 대비). */
async function findChild(parent: FileSystemDirectoryHandle, name: string): Promise<FileSystemDirectoryHandle | null> {
  const entries = (parent as unknown as { entries(): AsyncIterable<[string, FileSystemHandle]> }).entries()
  for await (const [childName, handle] of entries) {
    if (handle.kind === 'directory' && childName.toLowerCase() === name.toLowerCase()) {
      return parent.getDirectoryHandle(childName)
    }
  }
  return null
}

/**
 * 사용자가 고른 폴더에서 AddOns 폴더를 찾는다.
 * - AddOns 폴더를 직접 골랐으면 그대로
 * - 와우 설치 폴더(…/World of Warcraft)나 Interface 폴더를 골랐으면 안쪽의 Interface/AddOns
 * 엉뚱한 폴더를 골라 다른 파일을 지우는 사고를 막기 위해, 찾지 못하면 오류로 돌려보낸다.
 */
export async function resolveAddonsFolder(picked: FileSystemDirectoryHandle): Promise<FileSystemDirectoryHandle> {
  if (picked.name.toLowerCase() === 'addons') return picked

  const direct = await findChild(picked, 'AddOns')
  if (direct && picked.name.toLowerCase() === 'interface') return direct

  const iface = await findChild(picked, 'Interface')
  const nested = iface ? await findChild(iface, 'AddOns') : null
  if (nested) return nested

  throw new Error(
    `'${picked.name}' 안에서 Interface\\AddOns 폴더를 찾지 못했습니다. ` +
      '와우 설치 폴더(World of Warcraft) 또는 AddOns 폴더를 선택해 주세요.',
  )
}

/** 클라이언트 빌드 번호(Interface) → 게임 버전 표기. 알려진 클라이언트만 표로 두고 나머지는 계산한다. */
const KNOWN: Record<number, string> = {
  11200: '1.12.1',
  20400: '2.4.3',
  30300: '3.3.5',
  40300: '4.3.4',
  50400: '5.4.8',
}

export function interfaceToGameVersion(n: number): string {
  if (KNOWN[n]) return KNOWN[n]
  const major = Math.floor(n / 10000)
  const minor = Math.floor(n / 100) % 100
  const patch = n % 100
  return `${major}.${minor}.${patch}`
}

/** 판단할 애드온이 없을 때 쓰는 표시. 게임 버전은 화면에 보여 주는 이름표일 뿐 설치 판단에는 쓰지 않는다. */
const UNKNOWN_GAME_VERSION = '미확인'

/**
 * 설치된 애드온의 .toc `## Interface:` 값 중 가장 많은 값으로 게임 버전을 추정한다.
 * 애드온이 없어 판단할 수 없으면 '미확인'으로 둔다.
 * 폴더가 많으면 오래 걸리므로 일부만 본다.
 */
export async function detectGameVersion(addons: FileSystemDirectoryHandle): Promise<string> {
  const counts = new Map<number, number>()
  let seen = 0
  const entries = (addons as unknown as { entries(): AsyncIterable<[string, FileSystemHandle]> }).entries()
  for await (const [name, handle] of entries) {
    if (handle.kind !== 'directory' || name.startsWith('.')) continue
    const dir = await addons.getDirectoryHandle(name)
    const data = await readFileAt(dir, `${name}.toc`)
    if (!data) continue
    const n = parseToc(new TextDecoder().decode(data)).interfaceVersion
    if (n != null) counts.set(n, (counts.get(n) ?? 0) + 1)
    if (++seen >= 40) break
  }
  let best: number | null = null
  for (const [n, c] of counts) {
    if (best == null || c > (counts.get(best) ?? 0)) best = n
  }
  return best != null ? interfaceToGameVersion(best) : UNKNOWN_GAME_VERSION
}
