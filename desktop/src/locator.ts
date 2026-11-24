import { execFile } from 'node:child_process'
import * as fs from 'node:fs'
import * as path from 'node:path'

/** AddOns 폴더인지(…\Interface\AddOns) 이름으로 확인한다. 이 형태가 아니면 연결 대상으로 받지 않는다. */
export function isAddonsPath(p: string): boolean {
  const resolved = path.resolve(p)
  return path.basename(resolved).toLowerCase() === 'addons'
    && path.basename(path.dirname(resolved)).toLowerCase() === 'interface'
}

async function isDir(p: string): Promise<boolean> {
  try {
    return (await fs.promises.stat(p)).isDirectory()
  } catch {
    return false
  }
}

/**
 * 사용자가 고른 폴더에서 AddOns 폴더(들)를 찾는다.
 * - AddOns 를 직접 골랐으면 그대로, Interface 나 게임 폴더를 골랐으면 안쪽에서 찾는다.
 * - World of Warcraft 폴더처럼 `_retail_`, `_forever_` 같은 클라이언트 하위 폴더가 있으면 각각 찾는다.
 */
export async function resolveAddonsPaths(selected: string): Promise<string[]> {
  const base = path.resolve(selected)
  if (isAddonsPath(base)) return (await isDir(base)) ? [base] : []

  if (path.basename(base).toLowerCase() === 'interface') {
    const addons = path.join(base, 'AddOns')
    return (await isDir(addons)) ? [addons] : []
  }

  const nested = path.join(base, 'Interface', 'AddOns')
  if (await isDir(nested)) return [nested]

  let entries: fs.Dirent[]
  try {
    entries = await fs.promises.readdir(base, { withFileTypes: true })
  } catch {
    return []
  }
  const found: string[] = []
  for (const e of entries) {
    if (e.isDirectory() && /^_.+_$/.test(e.name)) {
      const addons = path.join(base, e.name, 'Interface', 'AddOns')
      if (await isDir(addons)) found.push(addons)
    }
  }
  return found
}

/** Blizzard 설치 정보가 있으면 그 경로. 없거나 Windows 가 아니면 null. */
function registryInstallPath(): Promise<string | null> {
  if (process.platform !== 'win32') return Promise.resolve(null)
  return new Promise((resolve) => {
    execFile(
      'reg',
      ['query', 'HKLM\\SOFTWARE\\WOW6432Node\\Blizzard Entertainment\\World of Warcraft', '/v', 'InstallPath'],
      { timeout: 3000, windowsHide: true },
      (err, stdout) => {
        if (err) return resolve(null)
        const m = /InstallPath\s+REG_SZ\s+(.+)/i.exec(String(stdout))
        resolve(m ? m[1].trim() : null)
      },
    )
  })
}

function candidateGameRoots(): string[] {
  const roots: string[] = []
  if (process.platform === 'win32') {
    for (const env of ['ProgramFiles(x86)', 'ProgramFiles']) {
      const dir = process.env[env]
      if (dir) roots.push(path.join(dir, 'World of Warcraft'))
    }
    // 드라이브마다 흔한 설치 위치. 네트워크 드라이브가 느릴 수 있어 일부만 본다.
    for (const letter of 'CDEFGH') {
      roots.push(`${letter}:\\World of Warcraft`)
      roots.push(`${letter}:\\Games\\World of Warcraft`)
      roots.push(`${letter}:\\Program Files (x86)\\World of Warcraft`)
    }
  } else if (process.platform === 'darwin') {
    roots.push('/Applications/World of Warcraft')
  }
  return roots
}

/**
 * 와우 포에버 클라이언트 폴더 이름. 기본 설치(World of Warcraft\_forever_\)에서 고정으로 쓰이는 식별 폴더라서
 * 자동 검색은 이 이름만 찾는다(리테일 `_retail_`, 클래식 `_classic_` 등은 대상이 아니다).
 */
export const FOREVER_DIR = '_forever_'

/** 선택한 폴더 아래의 AddOns 경로가 포에버 클라이언트 것인지(경로에 `_forever_` 폴더가 있는지). */
export function isForeverAddonsPath(p: string): boolean {
  return path.resolve(p).split(path.sep).some((s) => s.toLowerCase() === FOREVER_DIR)
}

/**
 * 게임 폴더(World of Warcraft) 또는 `_forever_` 폴더를 받아 포에버의 AddOns 경로를 돌려준다. 없으면 null.
 * 레지스트리 값처럼 어느 쪽이 올지 모르는 경우를 위해 둘 다 받는다.
 */
export async function findForeverAddons(gameRootOrProduct: string): Promise<string | null> {
  const base = path.resolve(gameRootOrProduct)
  const product = path.basename(base).toLowerCase() === FOREVER_DIR ? base : path.join(base, FOREVER_DIR)
  const addons = path.join(product, 'Interface', 'AddOns')
  return (await isDir(addons)) ? addons : null
}

/** PC 에서 와우 포에버의 AddOns 폴더를 찾는다. 중복(대소문자 차이 포함)은 합친다. */
export async function detectAddonsPaths(): Promise<string[]> {
  const roots = candidateGameRoots()
  const registry = await registryInstallPath()
  if (registry) roots.unshift(registry, path.dirname(registry))

  const seen = new Set<string>()
  const result: string[] = []
  for (const root of roots) {
    const found = await findForeverAddons(root)
    if (!found) continue
    const key = found.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    result.push(found)
  }
  return result
}
