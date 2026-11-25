// 백엔드 응답/요청 모델 (서버의 *Responses / *Params 와 맞춘다)

export interface Environment {
  id: number
  name: string
  gameVersion: string
}

export interface GameVersionGroup {
  typeName: string
  versions: string[]
}

export type UpdateStatus = 'UP_TO_DATE' | 'UPDATE_AVAILABLE' | 'UNKNOWN' | 'NO_FILE'

export interface InstalledAddon {
  projectId: number
  name: string
  logoUrl: string | null
  folders: string[]
  installedVersion: string | null
  installedFileId: number | null
  latestFileId: number | null
  latestFileName: string | null
  status: UpdateStatus
  downloadable: boolean
  websiteUrl: string | null
}

export interface ScanResponse {
  addons: InstalledAddon[]
  unmatchedFolders: string[]
}

export interface ScanEntry {
  folderName: string
  tocVersion: string | null
  projectId: number | null
  fingerprint: number | null
}

export interface SearchItem {
  projectId: number
  name: string
  summary: string
  logoUrl: string | null
  downloadCount: number
  authors: string
  websiteUrl: string | null
  latestFileId: number | null
  latestFileName: string | null
  downloadable: boolean
  /** 마지막으로 파일이 올라온 시각(ISO) */
  lastUpdated: string | null
  thumbsUpCount: number
  categories: string[]
  /** 관리가 중단됐거나 더 이상 제공되지 않는 애드온이면 이유 */
  warning: string | null
}

/** 검색 정렬. 서버가 허용한 이름만 쓴다. */
export type SearchSort = 'POPULARITY' | 'UPDATED' | 'DOWNLOADS' | 'NAME' | 'RATING'

export interface CategoryInfo {
  id: number
  name: string
}

export interface AddonDetail {
  projectId: number
  name: string
  summary: string
  /** HTML 을 지운 글자(서버가 변환). 텍스트로만 그린다. */
  description: string
  logoUrl: string | null
  screenshots: { title: string | null; thumbnailUrl: string | null; url: string }[]
  categories: string[]
  authors: { name: string; url: string | null }[]
  websiteUrl: string | null
  wikiUrl: string | null
  issuesUrl: string | null
  sourceUrl: string | null
  downloadCount: number
  thumbsUpCount: number
  license: string | null
  dateCreated: string | null
  lastUpdated: string | null
  warning: string | null
  downloadable: boolean
}

export type ReleaseType = 'RELEASE' | 'BETA' | 'ALPHA'

export interface AddonFile {
  fileId: number
  displayName: string
  fileName: string
  releaseType: ReleaseType
  fileDate: string | null
  fileLength: number
  downloadCount: number
  gameVersions: string[]
  /** 이 파일이 설치하는 폴더 이름들 */
  folders: string[]
}

export interface SearchResponse {
  items: SearchItem[]
  totalCount: number
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

export interface InstallReport {
  projectId: number
  fileId: number
  tocVersion: string | null
  folders: string[]
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

/** 설치에 필요한 다른 애드온(필수 의존성) */
export interface DependencyInfo {
  projectId: number
  name: string
  /** null 이면 이 게임 버전용 파일이 없어 설치할 수 없다 */
  latestFileId: number | null
  latestFileName: string | null
  /** false 면 작성자가 외부 배포를 막아 자동 설치가 불가능하다 */
  downloadable: boolean
}
