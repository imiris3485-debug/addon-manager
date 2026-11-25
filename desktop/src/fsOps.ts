import * as fs from 'node:fs'
import * as path from 'node:path'
import { assertNoLinks, FsError, resolveInside, validateSegment, validateSegments } from './pathGuard'

export interface FsEntry {
  name: string
  kind: 'file' | 'directory'
}

export interface FsOpsOptions {
  /** 한 번에 읽고 쓸 수 있는 파일 크기 상한(바이트) */
  maxFileBytes?: number
  /** true 인 키는 읽기만 허용한다(자료실 원본 폴더). 쓰기·폴더 만들기·삭제는 거부한다. */
  isReadOnly?: (key: string) => boolean
}

const DEFAULT_MAX_FILE_BYTES = 300 * 1024 * 1024

/** Node 오류를 화면에 돌려줄 수 있는 형태로 바꾼다. 경로가 섞인 원본 메시지는 노출하지 않는다. */
export function toFsError(e: unknown): FsError {
  if (e instanceof FsError) return e
  const code = (e as NodeJS.ErrnoException | undefined)?.code
  if (code === 'ENOENT') return new FsError('NotFound', '파일이나 폴더를 찾을 수 없습니다.')
  if (code === 'EEXIST' || code === 'ENOTDIR' || code === 'EISDIR' || code === 'ENOTEMPTY') {
    return new FsError('Invalid', '요청한 작업을 할 수 없는 경로입니다.')
  }
  if (code === 'EPERM' || code === 'EACCES' || code === 'EBUSY') {
    return new FsError('Other', '권한이 없거나 다른 프로그램이 사용 중입니다. 게임을 종료한 뒤 다시 시도하세요.')
  }
  return new FsError('Other', '파일 작업에 실패했습니다.')
}

/**
 * 연결된 AddOns 폴더(root) 안에서만 동작하는 파일 작업.
 * 모든 작업은 키 → 기준 폴더 → 경로 조각 검증 → 링크 검사 순서를 거친다.
 * ⚠️ 검사와 실제 작업 사이의 짧은 틈에 파일을 바꿔치는 공격(TOCTOU)은 이 PC 에 이미 접근한 상대만 가능하다.
 */
export class FsOps {
  private readonly maxFileBytes: number
  private readonly isReadOnly: (key: string) => boolean

  constructor(
    private readonly rootOf: (key: string) => string | null,
    options: FsOpsOptions = {},
  ) {
    this.maxFileBytes = options.maxFileBytes ?? DEFAULT_MAX_FILE_BYTES
    this.isReadOnly = options.isReadOnly ?? (() => false)
  }

  /** 읽기 전용 폴더(자료실 원본)에는 무엇도 쓰거나 지우지 못한다. 원본이 앱 때문에 바뀌면 안 된다. */
  private requireWritable(key: unknown): void {
    if (typeof key === 'string' && this.isReadOnly(key)) {
      throw new FsError('Invalid', '읽기 전용 폴더입니다. 이 폴더는 바꿀 수 없습니다.')
    }
  }

  private async locate(key: unknown, rel: unknown) {
    if (typeof key !== 'string') throw new FsError('Invalid', '잘못된 요청입니다.')
    const root = this.rootOf(key)
    if (!root) throw new FsError('NotFound', 'AddOns 폴더가 연결되지 않았습니다.')
    let rootReal: string
    try {
      // 선택한 폴더 자체가 정션을 거쳐 온 경우를 위해 실제 위치를 기준으로 삼는다
      rootReal = await fs.promises.realpath(root)
    } catch {
      throw new FsError('NotFound', 'AddOns 폴더를 찾을 수 없습니다. 게임 폴더가 이동되었는지 확인하세요.')
    }
    const segs = validateSegments(rel)
    await assertNoLinks(rootReal, segs)
    return { segs, full: resolveInside(rootReal, segs) }
  }

  private async lstatOrNull(p: string): Promise<fs.Stats | null> {
    try {
      return await fs.promises.lstat(p)
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === 'ENOENT') return null
      throw e
    }
  }

  async exists(key: unknown): Promise<boolean> {
    if (typeof key !== 'string') return false
    const root = this.rootOf(key)
    if (!root) return false
    try {
      return (await fs.promises.stat(root)).isDirectory()
    } catch {
      return false
    }
  }

  async list(key: unknown, rel: unknown): Promise<FsEntry[]> {
    const { full } = await this.locate(key, rel)
    const entries = await fs.promises.readdir(full, { withFileTypes: true })
    const result: FsEntry[] = []
    for (const e of entries) {
      // 링크는 따라가지 않으므로 목록에서도 뺀다. 화면이 참조할 수 없는 이름도 뺀다.
      if (!e.isFile() && !e.isDirectory()) continue
      try {
        validateSegment(e.name)
      } catch {
        continue
      }
      result.push({ name: e.name, kind: e.isDirectory() ? 'directory' : 'file' })
    }
    return result
  }

  async stat(key: unknown, rel: unknown): Promise<'file' | 'directory' | null> {
    const { full } = await this.locate(key, rel)
    const st = await this.lstatOrNull(full)
    if (!st) return null
    if (st.isDirectory()) return 'directory'
    if (st.isFile()) return 'file'
    return null
  }

  async read(key: unknown, rel: unknown): Promise<Uint8Array> {
    const { full } = await this.locate(key, rel)
    const st = await this.lstatOrNull(full)
    if (!st) throw new FsError('NotFound', '파일을 찾을 수 없습니다.')
    if (!st.isFile()) throw new FsError('Invalid', '파일이 아닙니다.')
    if (st.size > this.maxFileBytes) throw new FsError('TooLarge', '파일이 너무 큽니다.')
    return new Uint8Array(await fs.promises.readFile(full))
  }

  async write(key: unknown, rel: unknown, data: unknown): Promise<void> {
    this.requireWritable(key)
    const { segs, full } = await this.locate(key, rel)
    if (segs.length === 0) throw new FsError('Invalid', '파일 이름이 필요합니다.')
    if (!(data instanceof Uint8Array)) throw new FsError('Invalid', '잘못된 데이터입니다.')
    if (data.byteLength > this.maxFileBytes) throw new FsError('TooLarge', '파일이 너무 큽니다.')
    await fs.promises.mkdir(path.dirname(full), { recursive: true })
    const st = await this.lstatOrNull(full)
    if (st && !st.isFile()) throw new FsError('Invalid', '같은 이름의 폴더가 이미 있습니다.')
    await fs.promises.writeFile(full, data)
  }

  async mkdir(key: unknown, rel: unknown): Promise<void> {
    this.requireWritable(key)
    const { full } = await this.locate(key, rel)
    await fs.promises.mkdir(full, { recursive: true })
  }

  async remove(key: unknown, rel: unknown, recursive: unknown): Promise<void> {
    this.requireWritable(key)
    const { segs, full } = await this.locate(key, rel)
    // 기준 폴더(AddOns) 자체를 지우면 설치된 애드온이 전부 사라진다
    if (segs.length === 0) throw new FsError('Invalid', 'AddOns 폴더 자체는 삭제할 수 없습니다.')
    const st = await this.lstatOrNull(full)
    if (!st) throw new FsError('NotFound', '파일이나 폴더를 찾을 수 없습니다.')
    await fs.promises.rm(full, { recursive: recursive === true, force: false })
  }
}
