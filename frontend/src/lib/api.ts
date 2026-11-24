import { clearToken, getToken } from './auth'
import type {
  AccountStatus, AdminAccount, Environment, GameVersionGroup, InstallReport, ReleaseInfo, ScanEntry, ScanResponse,
  SearchResponse, UploadStart,
} from './types'

export class ApiError extends Error {
  status: number
  code?: string
  errors?: { field: string; reason: string }[]

  constructor(status: number, message: string, code?: string, errors?: { field: string; reason: string }[]) {
    super(message)
    this.status = status
    this.code = code
    this.errors = errors
  }
}

async function toError(res: Response): Promise<ApiError> {
  let body: { code?: string; message?: string; errors?: { field: string; reason: string }[] } = {}
  try {
    body = await res.json()
  } catch {
    /* 본문이 JSON 이 아니면 상태코드만 사용 */
  }
  const detail = body.errors?.length ? ` (${body.errors.map((e) => `${e.field}: ${e.reason}`).join(', ')})` : ''
  return new ApiError(res.status, (body.message ?? `요청에 실패했습니다 (${res.status})`) + detail, body.code, body.errors)
}

async function send(path: string, init: RequestInit = {}): Promise<Response> {
  const token = getToken()
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  // 로그인·가입에는 토큰을 붙이지 않는다. 서버는 공개 경로에서도 Bearer 토큰이 있으면 검증하므로,
  // 만료됐거나 서명키가 바뀐 옛 토큰이 남아 있으면 비밀번호가 맞아도 401 이 난다.
  if (token && !path.startsWith('/auth/')) headers.set('Authorization', `Bearer ${token}`)

  const res = await fetch(`/api/v1${path}`, { ...init, headers })
  // 토큰이 만료/무효면 로그인 화면으로 보낸다. 로그인·가입 요청 자체의 401 은 그대로 오류로 보여준다.
  if (res.status === 401 && token && !path.startsWith('/auth/')) {
    clearToken()
  }
  if (!res.ok) throw await toError(res)
  return res
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  return (await send(path, init)).json() as Promise<T>
}

async function empty(path: string, init?: RequestInit): Promise<void> {
  await send(path, init)
}

const body = (data: unknown): RequestInit => ({ method: 'POST', body: JSON.stringify(data) })

export const api = {
  // 인증
  login: (loginId: string, password: string) =>
    json<{ accessToken: string; expiresIn: number }>('/auth/login', body({ loginId, password })),
  signup: (loginId: string, password: string, name: string) =>
    empty('/auth/signup', body({ loginId, password, name })),

  // 게임 환경
  environments: () => json<Environment[]>('/environments'),
  createEnvironment: (name: string, gameVersion: string) =>
    json<Environment>('/environments', body({ name, gameVersion })),
  updateEnvironment: (id: number, name: string, gameVersion: string) =>
    empty(`/environments/${id}`, { method: 'PUT', body: JSON.stringify({ name, gameVersion }) }),
  deleteEnvironment: (id: number) => empty(`/environments/${id}`, { method: 'DELETE' }),

  // 애드온
  gameVersions: () => json<GameVersionGroup[]>('/addons/game-versions'),
  scan: (environmentId: number, entries: ScanEntry[]) =>
    json<ScanResponse>(`/environments/${environmentId}/scan`, body({ entries })),
  reportInstalled: (environmentId: number, report: InstallReport) =>
    empty(`/environments/${environmentId}/installed`, body(report)),
  removeInstalled: (environmentId: number, projectId: number) =>
    empty(`/environments/${environmentId}/installed/${projectId}`, { method: 'DELETE' }),
  search: (environmentId: number, q: string, page: number) =>
    json<SearchResponse>(`/addons/search?environmentId=${environmentId}&q=${encodeURIComponent(q)}&page=${page}`),
  download: async (projectId: number, fileId: number): Promise<Uint8Array> => {
    const res = await send(`/addons/${projectId}/files/${fileId}/download`)
    return new Uint8Array(await res.arrayBuffer())
  },

  // 관리자: 데스크톱 앱 배포(설치 파일은 프록시 크기 제한을 피하려고 조각으로 올린다)
  adminRelease: () => json<ReleaseInfo>('/admin/app-release'),
  adminReleaseStart: () => json<UploadStart>('/admin/app-release/uploads', { method: 'POST' }),
  adminReleaseChunk: (uploadId: string, index: number, chunk: Blob) =>
    empty(`/admin/app-release/uploads/${uploadId}/chunks/${index}`, {
      method: 'PUT', body: chunk, headers: { 'Content-Type': 'application/octet-stream' },
    }),
  adminReleaseComplete: (uploadId: string, latestYml: string) =>
    json<ReleaseInfo>(`/admin/app-release/uploads/${uploadId}/complete`, body({ latestYml })),
  adminReleaseAbort: (uploadId: string) =>
    empty(`/admin/app-release/uploads/${uploadId}`, { method: 'DELETE' }),

  // 관리자
  adminAccounts: (status?: AccountStatus) =>
    json<AdminAccount[]>(`/admin/accounts${status ? `?status=${status}` : ''}`),
  approveAccount: (id: number) => empty(`/admin/accounts/${id}/approve`, { method: 'PUT' }),
  rejectAccount: (id: number) => empty(`/admin/accounts/${id}/reject`, { method: 'PUT' }),
}
