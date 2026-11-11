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
