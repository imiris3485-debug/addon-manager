// 데스크톱 앱(Electron) 안에서 열렸을 때 PC 폴더를 다루는 어댑터.
// 앱이 노출한 window.addonFs 를 File System Access API 의 폴더 핸들처럼 보이게 감싸서,
// 스캔·지문·설치 코드는 브라우저일 때와 똑같이 동작한다.
// 실제 허용 여부(출처·경로·링크·크기)는 앱(main)이 매 요청마다 다시 검사하며, 이 파일은 그 검사를 대신하지 않는다.

type FailCode = 'NotFound' | 'Invalid' | 'TooLarge' | 'Other'
type Result<T> = { ok: true; value: T } | { ok: false; code: FailCode; message: string }

export interface DetectedFolder {
  /** 앱이 발급한 불투명 키. 경로 문자열이 아니다. */
  key: string
  /** 사용자에게 보여 줄 경로 */
  label: string
}

interface AddonFsBridge {
  detect(): Promise<Result<DetectedFolder[]>>
  pick(): Promise<Result<DetectedFolder | null>>
  /** 관리자: 자료실 원본 폴더를 OS 선택 창에서 고른다(읽기 전용으로만 열린다) */
  pickLibrary(): Promise<Result<DetectedFolder | null>>
  /** 이미 정해 둔 자료실 원본 폴더. 없거나 사라졌으면 null */
  libraryInfo(): Promise<Result<DetectedFolder | null>>
  bind(key: string, envId: number): Promise<Result<void>>
  exists(key: string): Promise<Result<boolean>>
  list(key: string, rel: string[]): Promise<Result<{ name: string; kind: 'file' | 'directory' }[]>>
  stat(key: string, rel: string[]): Promise<Result<'file' | 'directory' | null>>
  read(key: string, rel: string[]): Promise<Result<Uint8Array>>
  write(key: string, rel: string[], data: Uint8Array): Promise<Result<void>>
  mkdir(key: string, rel: string[]): Promise<Result<void>>
  remove(key: string, rel: string[], recursive: boolean): Promise<Result<void>>
  /** 앱 버전·업데이트(이 기능이 생기기 전의 옛 앱에는 없다) */
  appVersion?(): Promise<Result<string>>
  checkUpdate?(): Promise<Result<AppUpdateStatus>>
  installUpdate?(): Promise<Result<void>>
}

export interface AppUpdateStatus {
  state: 'dev' | 'latest' | 'downloaded' | 'error'
  current: string
  latest?: string
  message?: string
}

declare global {
  interface Window {
    addonFs?: AddonFsBridge
  }
}

export const isDesktop = (): boolean => typeof window !== 'undefined' && window.addonFs !== undefined

function bridge(): AddonFsBridge {
  if (!window.addonFs) throw new Error('데스크톱 앱에서만 사용할 수 있습니다.')
  return window.addonFs
}

/** 앱이 돌려준 실패를 File System Access API 와 같은 이름의 오류로 바꾼다(기존 코드가 NotFoundError 로 분기한다). */
function unwrap<T>(result: Result<T>): T {
  if (result.ok) return result.value
  if (result.code === 'NotFound') throw new DOMException(result.message, 'NotFoundError')
  if (result.code === 'Invalid') throw new DOMException(result.message, 'TypeMismatchError')
  throw new Error(result.message)
}

function toBytes(data: unknown): Uint8Array {
  if (data instanceof Uint8Array) return data
  if (data instanceof ArrayBuffer) return new Uint8Array(data)
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
  throw new TypeError('지원하지 않는 데이터 형식입니다.')
}

class NativeFileHandle {
  readonly kind = 'file' as const

  constructor(
    private readonly key: string,
    private readonly rel: string[],
  ) {}

  get name(): string {
    return this.rel[this.rel.length - 1]
  }

  async getFile(): Promise<File> {
    const data = unwrap(await bridge().read(this.key, this.rel))
    return new File([data as unknown as BlobPart], this.name)
  }

  async createWritable() {
    const chunks: Uint8Array[] = []
    const { key, rel } = this
    return {
      async write(data: unknown) {
        chunks.push(toBytes(data))
      },
      async close() {
        const total = chunks.reduce((n, c) => n + c.byteLength, 0)
        const merged = new Uint8Array(total)
        let offset = 0
        for (const c of chunks) {
          merged.set(c, offset)
          offset += c.byteLength
        }
        unwrap(await bridge().write(key, rel, merged))
      },
    }
  }
}

export class NativeDirHandle {
  readonly kind = 'directory' as const

  constructor(
    readonly key: string,
    private readonly rel: string[] = [],
    private readonly rootName: string = 'AddOns',
  ) {}

  /** 루트의 이름. AddOns 폴더는 'AddOns', 자료실 원본은 관리자가 고른 폴더의 이름이다. */
  get name(): string {
    return this.rel.length === 0 ? this.rootName : this.rel[this.rel.length - 1]
  }

  private async kindOf(name: string): Promise<'file' | 'directory' | null> {
    return unwrap(await bridge().stat(this.key, [...this.rel, name]))
  }

  async getDirectoryHandle(name: string, options?: { create?: boolean }): Promise<NativeDirHandle> {
    const kind = await this.kindOf(name)
    if (kind === 'file') throw new DOMException('폴더가 아닙니다.', 'TypeMismatchError')
    if (kind === null) {
      if (!options?.create) throw new DOMException('폴더를 찾을 수 없습니다.', 'NotFoundError')
      unwrap(await bridge().mkdir(this.key, [...this.rel, name]))
    }
    return new NativeDirHandle(this.key, [...this.rel, name], this.rootName)
  }

  async getFileHandle(name: string, options?: { create?: boolean }): Promise<NativeFileHandle> {
    const kind = await this.kindOf(name)
    if (kind === 'directory') throw new DOMException('파일이 아닙니다.', 'TypeMismatchError')
    if (kind === null) {
      if (!options?.create) throw new DOMException('파일을 찾을 수 없습니다.', 'NotFoundError')
      unwrap(await bridge().write(this.key, [...this.rel, name], new Uint8Array(0)))
    }
    return new NativeFileHandle(this.key, [...this.rel, name])
  }

  async removeEntry(name: string, options?: { recursive?: boolean }): Promise<void> {
    unwrap(await bridge().remove(this.key, [...this.rel, name], options?.recursive === true))
  }

  async *entries(): AsyncGenerator<[string, NativeDirHandle | NativeFileHandle]> {
    for (const e of unwrap(await bridge().list(this.key, this.rel))) {
      yield [e.name, e.kind === 'directory' ? new NativeDirHandle(this.key, [...this.rel, e.name], this.rootName) : new NativeFileHandle(this.key, [...this.rel, e.name])]
    }
  }
}

export const asDirectoryHandle = (h: NativeDirHandle) => h as unknown as FileSystemDirectoryHandle

/** 앱이 PC 에서 찾아낸 AddOns 폴더 후보. 못 찾으면 빈 배열. */
export async function detectFolders(): Promise<DetectedFolder[]> {
  return unwrap(await bridge().detect())
}

/** OS 폴더 선택 창을 연다. 취소하면 null. */
export async function pickNative(): Promise<{ handle: FileSystemDirectoryHandle; label: string } | null> {
  const picked = unwrap(await bridge().pick())
  return picked ? { handle: asDirectoryHandle(new NativeDirHandle(picked.key)), label: picked.label } : null
}

/** 앱 버전. 버전 조회가 없는 옛 앱이면 null(그 자체가 "앱을 업데이트해야 한다"는 뜻이다). */
export async function getAppVersion(): Promise<string | null> {
  const b = bridge()
  if (typeof b.appVersion !== 'function') return null
  return unwrap(await b.appVersion())
}

/** 새 버전을 확인하고 있으면 받아 둔다. 옛 앱이면 null. */
export async function checkAppUpdate(): Promise<AppUpdateStatus | null> {
  const b = bridge()
  if (typeof b.checkUpdate !== 'function') return null
  return unwrap(await b.checkUpdate())
}

/** 받아 둔 새 버전을 적용하며 앱을 다시 시작한다. */
export async function installAppUpdate(): Promise<void> {
  const b = bridge()
  if (typeof b.installUpdate === 'function') unwrap(await b.installUpdate())
}

/**
 * 웹은 서버에서 바로 새로 배포되지만 앱은 PC 에 설치돼 있어서, 옛 앱에는 자료실 폴더 기능이 없다.
 * 그때 `pickLibrary is not a function` 같은 알 수 없는 오류 대신 해야 할 일을 알려 준다.
 */
function requireNewApp() {
  const b = bridge() as Partial<AddonFsBridge>
  if (typeof b.pickLibrary !== 'function' || typeof b.libraryInfo !== 'function') {
    throw new Error('설치된 데스크톱 앱이 옛 버전이라 이 기능을 쓸 수 없습니다. 앱을 최신 버전으로 업데이트(재설치)해 주세요.')
  }
}

/** 자료실 원본 폴더를 고른다(관리자). 취소하면 null. 읽기 전용 핸들이라 쓰기·삭제는 앱이 거부한다. */
export async function pickLibraryFolder(): Promise<{ handle: FileSystemDirectoryHandle; label: string } | null> {
  requireNewApp()
  const picked = unwrap(await bridge().pickLibrary())
  return picked ? { handle: libraryHandle(picked), label: picked.label } : null
}

/** 이미 정해 둔 자료실 원본 폴더. 없거나 사라졌으면 null. */
export async function getLibraryFolder(): Promise<{ handle: FileSystemDirectoryHandle; label: string } | null> {
  requireNewApp()
  const info = unwrap(await bridge().libraryInfo())
  return info ? { handle: libraryHandle(info), label: info.label } : null
}

function libraryHandle(f: DetectedFolder): FileSystemDirectoryHandle {
  const name = f.label.split(/[\\/]/).filter(Boolean).pop() ?? '자료실'
  return asDirectoryHandle(new NativeDirHandle(f.key, [], name))
}

export const handleFromKey = (key: string): FileSystemDirectoryHandle => asDirectoryHandle(new NativeDirHandle(key))

export async function bindNative(handle: FileSystemDirectoryHandle, envId: number): Promise<void> {
  unwrap(await bridge().bind((handle as unknown as NativeDirHandle).key, envId))
}

export async function nativeExists(envId: number): Promise<boolean> {
  return unwrap(await bridge().exists(String(envId)))
}

export const isNativeHandle = (h: FileSystemDirectoryHandle): boolean => (h as unknown) instanceof NativeDirHandle
