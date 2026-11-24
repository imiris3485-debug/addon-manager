import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useEnv } from '../lib/EnvContext'
import { installAddon } from '../lib/installer'
import { isDesktop } from '../lib/nativeFs'
import type { ScanResponse, SearchItem } from '../lib/types'
import { useFolder } from '../lib/useFolder'

const PAGE_SIZE = 20

export default function SearchPage() {
  const { current, serverEnv, status } = useEnv()
  // 설치는 데스크톱 앱에서만 한다. 웹 브라우저에서는 검색만 하므로 서버에 등록된 환경의 게임 버전을 빌려 쓴다.
  const desktop = isDesktop()
  const env = desktop ? current : serverEnv
  const envId = env?.id ?? null
  const qc = useQueryClient()
  const folder = useFolder(desktop ? envId : null)

  const [input, setInput] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [message, setMessage] = useState<Record<number, string>>({})

  const { data, isFetching, error } = useQuery({
    queryKey: ['search', envId, q, page],
    queryFn: () => api.search(envId!, q, page),
    enabled: envId != null,
    placeholderData: (prev) => prev,
  })

  // 설치 여부는 마지막 스캔 결과 기준으로 표시한다
  const scan = qc.getQueryData<ScanResponse | null>(['scan', envId])
  const installedIds = new Set(scan?.addons.map((a) => a.projectId) ?? [])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setPage(0)
    setQ(input.trim())
  }

  const install = async (item: SearchItem) => {
    if (envId == null || item.latestFileId == null) return
    setMessage((m) => ({ ...m, [item.projectId]: '설치 중...' }))
    try {
      const root = await folder.requireAccess()
      await installAddon(envId, root, item.projectId, item.latestFileId)
      // 설치 후에는 스캔 결과가 낡았으므로 비워서 "설치된 애드온" 화면이 다시 스캔하게 한다
      qc.setQueryData(['scan', envId], null)
      setMessage((m) => ({ ...m, [item.projectId]: '설치 완료' }))
    } catch (e) {
      setMessage((m) => ({ ...m, [item.projectId]: `실패: ${(e as Error).message}` }))
    }
  }

  if (!env) {
    if (status === 'loading' || desktop) return <p className="text-slate-400">불러오는 중...</p>
    // 앱에서 폴더를 연결한 적이 없으면 게임 버전을 알 수 없어 검색할 수 없다
    return (
      <div className="card space-y-3 text-center">
        <p className="text-slate-300">먼저 데스크톱 앱에서 로그인해 와우 폴더를 연결해 주세요.</p>
        <p className="text-sm text-slate-400">연결하면 게임 버전이 자동으로 정해져 웹에서도 검색할 수 있습니다.</p>
        <Link className="btn btn-primary" to="/download">앱 다운로드</Link>
      </div>
    )
  }

  const totalPages = data ? Math.max(1, Math.ceil(data.totalCount / PAGE_SIZE)) : 1

  return (
    <div className="space-y-4">
      {!desktop && (
        <p className="rounded-md border border-indigo-500/30 bg-indigo-500/10 p-3 text-sm text-indigo-200">
          웹에서는 애드온 검색만 할 수 있습니다. 설치·업데이트는 <Link className="underline" to="/download">데스크톱 앱</Link>에서 하세요.
        </p>
      )}
      <form onSubmit={submit} className="flex gap-2">
        <input className="input" placeholder={`애드온 검색 (게임 버전 ${env.gameVersion})`}
               value={input} onChange={(e) => setInput(e.target.value)} />
        <button className="btn btn-primary">검색</button>
      </form>

      {error && <p className="text-sm text-rose-400">{(error as Error).message}</p>}
      {isFetching && <p className="text-sm text-slate-400">검색 중...</p>}

      <ul className="space-y-2">
        {data?.items.map((item) => (
          <li key={item.projectId} className="card flex flex-wrap items-start gap-3">
            {item.logoUrl
              ? <img src={item.logoUrl} alt="" className="h-12 w-12 rounded" />
              : <div className="h-12 w-12 rounded bg-slate-800" />}
            <div className="min-w-0 flex-1">
              <div className="font-medium">{item.name}</div>
              <div className="text-xs text-slate-400">
                {item.authors} · 다운로드 {item.downloadCount.toLocaleString()}
                {item.latestFileName && <> · {item.latestFileName}</>}
              </div>
              <p className="mt-1 line-clamp-2 text-sm text-slate-300">{item.summary}</p>
              {message[item.projectId] && <p className="mt-1 text-xs text-amber-300">{message[item.projectId]}</p>}
            </div>
            <div className="flex gap-2">
              {item.websiteUrl && <a className="btn" href={item.websiteUrl} target="_blank" rel="noreferrer">상세</a>}
              {installedIds.has(item.projectId)
                ? <span className="btn opacity-60">설치됨</span>
                : item.latestFileId == null
                  ? <span className="btn opacity-60">이 버전 파일 없음</span>
                  : !item.downloadable
                    ? <span className="btn opacity-60" title="작성자가 외부 배포를 막았습니다">직접 설치 필요</span>
                    : desktop
                      ? <button className="btn btn-primary" disabled={!folder.handle} onClick={() => install(item)}>설치</button>
                      : <Link className="btn" to="/download">앱에서 설치</Link>}
            </div>
          </li>
        ))}
      </ul>

      {data && data.items.length === 0 && <p className="text-slate-400">검색 결과가 없습니다.</p>}

      {data && totalPages > 1 && (
        <div className="flex items-center justify-center gap-3">
          <button className="btn" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>이전</button>
          <span className="text-sm text-slate-400">{page + 1} / {totalPages}</span>
          <button className="btn" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>다음</button>
        </div>
      )}
    </div>
  )
}
