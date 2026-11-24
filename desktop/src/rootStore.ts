import { randomUUID } from 'node:crypto'
import * as fs from 'node:fs'
import { isAddonsPath } from './locator'

const PENDING_KEY = /^p-[0-9a-f-]{36}$/
const ENV_KEY = /^[1-9][0-9]{0,17}$/
const MAX_PENDING = 20

/**
 * 연결된 AddOns 폴더의 경로를 보관한다. 경로는 이 프로그램(main)만 알고, 화면에는 키만 준다.
 * 화면이 경로 문자열을 직접 넘겨 임의의 폴더를 연결하는 일을 막기 위해서다.
 *
 * - 대기(pending) 키: 폴더 선택·자동 검색 직후. 아직 게임 환경(서버 ID)과 묶이기 전.
 * - 환경 키: 게임 환경 ID 로 묶여 파일에 저장된 연결.
 */
export class RootStore {
  private readonly pending = new Map<string, string>()
  private persisted: Record<string, string> = {}

  constructor(private readonly file: string) {
    try {
      const parsed: unknown = JSON.parse(fs.readFileSync(file, 'utf8'))
      if (parsed && typeof parsed === 'object') {
        for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
          // 파일이 바뀌어도 AddOns 폴더 형태가 아니면 믿지 않는다
          if (ENV_KEY.test(key) && typeof value === 'string' && isAddonsPath(value)) {
            this.persisted[key] = value
          }
        }
      }
    } catch {
      /* 파일이 없거나 깨졌으면 연결이 없는 상태로 시작한다 */
    }
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
    fs.writeFileSync(this.file, JSON.stringify(this.persisted, null, 2), 'utf8')
    this.pending.delete(pendingKey)
  }

  rootOf(key: string): string | null {
    if (PENDING_KEY.test(key)) return this.pending.get(key) ?? null
    if (ENV_KEY.test(key)) return this.persisted[key] ?? null
    return null
  }
}
