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
}
