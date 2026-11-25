// 백엔드 응답/요청 모델 (서버의 *Responses / *Params 와 맞춘다)

export interface Environment {
  id: number
  name: string
  gameVersion: string
}

export type AccountStatus = 'PENDING' | 'ACTIVE' | 'REJECTED'

export interface AdminAccount {
  id: number
  loginId: string
  name: string
  role: string
  status: AccountStatus
  createdAt: string
}

/** 현재 게시된 데스크톱 앱 */
export interface ReleaseInfo {
  published: boolean
  version: string | null
  size: number
  releaseDate: string | null
}

export interface UploadStart {
  uploadId: string
  /** 한 번에 보낼 수 있는 최대 조각 크기(바이트) */
  chunkSize: number
  /** 올릴 수 있는 설치 파일의 최대 크기(바이트) */
  maxBytes: number
}

/** 자료실 항목 하나 = 애드온 폴더 하나. 관리자가 올리고 로그인한 사용자가 받는다. */
export interface LibraryAddon {
  id: string
  /** 애드온 폴더 이름 */
  name: string
  version: string | null
  /** 폴더 내용의 해시. 같으면 내용이 같다(설치·업데이트 판단에 쓴다) */
  contentHash: string
  /** 같이 설치할 폴더들(.toc 의 Dependencies/RequiredDeps + 관리자가 지정한 것) */
  requires: string[]
  /** 이 중 관리자가 따로 지정한 것(.toc 에 선언되지 않은 의존성 보충) */
  extraRequires: string[]
  /**
   * 같은 묶음에 딸린 모듈 폴더(이름이 `이름_` 으로 시작하는 자료실 애드온. 예: Details → Details_Compare2).
   * 서버가 주는 값이 아니라 자료실 목록에서 클라이언트가 계산한다(withModules).
   */
  modules: string[]
  fileSize: number
  /** 관리자 메모 */
  note: string | null
  updatedAt: string | null
}

/** 이 PC 의 AddOns(또는 자료실 원본) 폴더 안의 애드온 폴더 하나(.toc 가 있는 폴더). */
export interface LocalFolder {
  name: string
  version: string | null
  requires: string[]
  /** .toc 의 OptionalDeps(동기화할 때 원본 폴더에 있는 것만 함께 설치로 묶는다) */
  optionalRequires: string[]
}

/** 세팅(애드온 묶음). 만든 사람이 지정한 사람에게 공유한다. */
export interface Setup {
  /** 외부 식별자(UUID) */
  id: string
  name: string
  description: string | null
  /** 자료실에 지금 있는 애드온 이름 */
  addons: string[]
  /** 세팅에는 있지만 자료실에서 지워져 설치할 수 없는 애드온 */
  missingAddons: string[]
  ownerLoginId: string
  ownerName: string
  /** 내가 만든 세팅인가 */
  mine: boolean
  /** 공유 대상(만든 사람에게만 내려온다) */
  targets: Person[]
  updatedAt: string | null
  /** 편지를 보낸 시각 */
  sentAt: string | null
  /** 받은 편지를 열어 봤는가(내가 만든 것은 항상 true) */
  read: boolean
}

/** 보관함의 기록 하나(내 애드온 목록). 폴더를 다 지워도 이 기록으로 그대로 다시 설치할 수 있다. */
export interface Snapshot {
  id: string
  /** AUTO: 앱이 자동으로 남김 / MANUAL: 이름을 붙여 직접 보관 */
  source: 'AUTO' | 'MANUAL'
  label: string | null
  gameVersion: string | null
  /** 자료실에 지금 있어 설치할 수 있는 애드온 */
  addons: string[]
  /** 기록에는 있지만 자료실에서 사라진 애드온 */
  missingAddons: string[]
  createdAt: string | null
}

export interface Person {
  loginId: string
  name: string
}

export interface SetupList {
  mine: Setup[]
  received: Setup[]
}

export interface SetupInput {
  name: string
  description: string
  addons: string[]
  /** 공유할 사람의 로그인 ID */
  targets: string[]
}

export interface InstallEvent {
  addonName: string
  action: 'INSTALLED' | 'UPDATED' | 'REMOVED'
  contentHash?: string
}
