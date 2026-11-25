import { randomUUID } from 'node:crypto'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { isAddonsPath } from './locator'

const PENDING_KEY = /^p-[0-9a-f-]{36}$/
const ENV_KEY = /^[1-9][0-9]{0,17}$/
const MAX_PENDING = 20

/** 자료실 원본 폴더의 키. 관리자가 애드온을 모아 두는 폴더이며 **읽기 전용**으로만 연다. */
export const LIBRARY_KEY = 'library'

/**
 * 연결된 폴더의 경로를 보관한다. 경로는 이 프로그램(main)만 알고, 화면에는 키만 준다.
 * 화면이 경로 문자열을 직접 넘겨 임의의 폴더를 연결하는 일을 막기 위해서다.
 *
 * - 대기(pending) 키: 폴더 선택·자동 검색 직후. 아직 게임 환경(서버 ID)과 묶이기 전.
 * - 환경 키: 게임 환경 ID 로 묶여 파일에 저장된 AddOns 폴더 연결(읽고 쓸 수 있다).
 * - 자료실 키('library'): 관리자가 OS 폴더 선택 창으로 고른 원본 폴더. 읽기만 가능하고 쓰기·삭제는 막는다.
 */
export class RootStore {
  private readonly pending = new Map<string, string>()
  private persisted: Record<string, string> = {}
  private library: string | null = null

  constructor(private readonly file: string) {
    try {
      const parsed: unknown = JSON.parse(fs.readFileSync(file, 'utf8'))
      if (parsed && typeof parsed === 'object') {
        for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
          if (typeof value !== 'string') continue
          // 파일이 바뀌어도 AddOns 폴더 형태가 아니면 믿지 않는다
          if (ENV_KEY.test(key) && isAddonsPath(value)) this.persisted[key] = value
          if (key === LIBRARY_KEY && isValidLibraryPath(value)) this.library = value
        }
      }
    } catch {
      /* 파일이 없거나 깨졌으면 연결이 없는 상태로 시작한다 */
    }
  }

  private save(): void {
    fs.writeFileSync(this.file, JSON.stringify({ ...this.persisted, ...(this.library ? { [LIBRARY_KEY]: this.library } : {}) }, null, 2), 'utf8')
  }

  /** 자료실 원본 폴더를 정한다. 사용자가 OS 선택 창에서 고른 경로만 넘겨야 한다. */
  setLibrary(dir: string): void {
    if (!isValidLibraryPath(dir)) throw new Error('이 폴더는 자료실 원본으로 쓸 수 없습니다. 드라이브 전체가 아닌 폴더를 선택하세요.')
    this.library = dir
    this.save()
  }

  getLibrary(): string | null {
    return this.library
  }

  isReadOnly(key: string): boolean {
    return key === LIBRARY_KEY
  }

  addPending(addonsPath: string): string {
    if (!isAddonsPath(addonsPath)) throw new Error('AddOns 폴더가 아닙니다.')
    if (this.pending.size >= MAX_PENDING) {
      const oldest = this.pending.keys().next().value
      if (oldest !== undefined) this.pending.delete(oldest)
    }
    const key = `p-${randomUUID()}`
    this.pending.set(key, addonsPath)
    return key
  }

  /** 대기 중인 연결을 게임 환경 ID 에 묶어 저장한다. */
  bind(pendingKey: unknown, envId: unknown): void {
    if (typeof pendingKey !== 'string' || !PENDING_KEY.test(pendingKey)) throw new Error('잘못된 키입니다.')
    if (typeof envId !== 'number' || !Number.isSafeInteger(envId) || envId <= 0) throw new Error('잘못된 환경입니다.')
    const addonsPath = this.pending.get(pendingKey)
    if (!addonsPath) throw new Error('선택 정보가 만료되었습니다. 폴더를 다시 선택하세요.')
    this.persisted[String(envId)] = addonsPath
    this.save()
    this.pending.delete(pendingKey)
  }

  rootOf(key: string): string | null {
    if (key === LIBRARY_KEY) return this.library
    if (PENDING_KEY.test(key)) return this.pending.get(key) ?? null
    if (ENV_KEY.test(key)) return this.persisted[key] ?? null
    return null
  }
}

/** 절대 경로이고 드라이브 전체(C:\ 등)가 아닌 폴더만 자료실 원본으로 쓴다. */
function isValidLibraryPath(p: string): boolean {
  if (!path.isAbsolute(p)) return false
  const resolved = path.resolve(p)
  return path.parse(resolved).root !== resolved
}
