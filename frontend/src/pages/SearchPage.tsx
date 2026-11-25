import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { AddonDetailModal } from '../components/AddonDetailModal'
import { api } from '../lib/api'
import { curseforgeInstallUrl, curseforgeLinkAttrs } from '../lib/curseforgeLink'
import { useEnv } from '../lib/EnvContext'
import { formatAgo, formatCount } from '../lib/format'
import { describeDependencies, installWithDependencies } from '../lib/installer'
import { isDesktop } from '../lib/nativeFs'
import type { SearchItem, SearchSort } from '../lib/types'
import { useFolder } from '../lib/useFolder'

const PAGE_SIZE = 20

const SORT_LABELS: Record<SearchSort, string> = {
  POPULARITY: '인기순',
  UPDATED: '최근 갱신순',
  DOWNLOADS: '다운로드 많은 순',
  NAME: '이름순',
  RATING: '평점순',
}

/** 2년 넘게 갱신되지 않았는지. 최신 게임 버전과 안 맞을 수 있어 목록에서 눈에 띄게 한다. */
const isStale = (iso: string | null) => !!iso && Date.now() - new Date(iso).getTime() > 2 * 365 * 86_400_000

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
  const [sort, setSort] = useState<SearchSort>('POPULARITY')
  const [categoryId, setCategoryId] = useState<number | null>(null)
  const [detailId, setDetailId] = useState<number | null>(null)
  const [message, setMessage] = useState<Record<number, string>>({})

  const { data, isFetching, error } = useQuery({
    queryKey: ['search', envId, q, page, sort, categoryId],
    queryFn: () => api.search(envId!, q, page, sort, categoryId),
    enabled: envId != null,
    placeholderData: (prev) => prev,
  })

  const { data: categories } = useQuery({
    queryKey: ['categories'], queryFn: api.categories, staleTime: 60 * 60 * 1000,
  })

  // 설치 여부는 서버의 설치 기록으로 표시한다. 스캔 결과(화면 캐시)는 설치하면 비워지고,
  // 스캔 화면을 열기 전에는 없어서 방금 설치한 것도 "설치" 버튼이 다시 보였다.
  const { data: installedList } = useQuery({
    queryKey: ['installed-ids', envId],
    queryFn: () => api.installedProjectIds(envId!),
    enabled: envId != null,
  })
  const installedIds = new Set(installedList ?? [])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setPage(0)
    setQ(input.trim())
  }

  /** 파일을 필수 의존성과 함께 설치하고 안내 글을 돌려준다. 목록과 상세 화면이 같이 쓴다. */
  const installFile = async (projectId: number, fileId: number, say: (text: string) => void): Promise<string> => {
    if (envId == null) throw new Error('게임 환경이 없습니다.')
    const root = await folder.requireAccess()
    // 필수 의존성(예: 퀘스트 DB)을 먼저 설치한다. 이미 설치된 것은 건너뛴다.
    const { dependencies } = await installWithDependencies(envId, root, projectId, fileId, installedIds, say)
    // 설치 후에는 스캔 결과가 낡았으므로 비워서 "설치된 애드온" 화면이 다시 스캔하게 한다
    qc.setQueryData(['scan', envId], null)
    await qc.invalidateQueries({ queryKey: ['installed-ids', envId] })
    return ['설치 완료', ...describeDependencies(dependencies)].join('\n')
  }

  const install = async (item: SearchItem) => {
    if (item.latestFileId == null) return
    const say = (text: string) => setMessage((m) => ({ ...m, [item.projectId]: text }))
    say('설치 중...')
    try {
      say(await installFile(item.projectId, item.latestFileId, say))
    } catch (e) {
      say(`실패: ${(e as Error).message}`)
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
      <form onSubmit={submit} className="flex flex-wrap gap-2">
        <input className="input min-w-48 flex-1" placeholder={`애드온 검색 (게임 버전 ${env.gameVersion})`}
               value={input} onChange={(e) => setInput(e.target.value)} />
        <select className="input !w-auto" aria-label="정렬" value={sort}
                onChange={(e) => { setSort(e.target.value as SearchSort); setPage(0) }}>
          {(Object.keys(SORT_LABELS) as SearchSort[]).map((s) => <option key={s} value={s}>{SORT_LABELS[s]}</option>)}
        </select>
        <select className="input !w-auto" aria-label="분류" value={categoryId ?? ''}
                onChange={(e) => { setCategoryId(e.target.value ? Number(e.target.value) : null); setPage(0) }}>
          <option value="">모든 분류</option>
          {categories?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        <button className="btn btn-primary">검색</button>
      </form>

      {error && <p className="text-sm text-rose-400">{(error as Error).message}</p>}
      {isFetching && <p className="text-sm text-slate-400">검색 중...</p>}

      <ul className="space-y-2">
        {data?.items.map((item) => (
          <li key={item.projectId} className="card flex flex-wrap items-start gap-3">
            {item.logoUrl
              ? <img src={item.logoUrl} alt="" referrerPolicy="no-referrer" className="h-12 w-12 rounded" />
              : <div className="h-12 w-12 rounded bg-slate-800" />}
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <button className="font-medium hover:underline" onClick={() => setDetailId(item.projectId)}>{item.name}</button>
                {item.categories.slice(0, 3).map((c) => (
                  <span key={c} className="rounded bg-slate-800 px-1.5 py-0.5 text-xs text-slate-400">{c}</span>
                ))}
              </div>
              <div className="text-xs text-slate-400">
                {item.authors} · 다운로드 {formatCount(item.downloadCount)} · 👍 {formatCount(item.thumbsUpCount)}
                {item.lastUpdated && (
                  <span className={isStale(item.lastUpdated) ? 'text-amber-400' : ''}> · 갱신 {formatAgo(item.lastUpdated)}</span>
                )}
                {item.latestFileName && <> · {item.latestFileName}</>}
              </div>
              <p className="mt-1 line-clamp-2 text-sm text-slate-300">{item.summary}</p>
              {item.warning && <p className="mt-1 text-xs text-amber-300">⚠ {item.warning}</p>}
              {message[item.projectId] && <p className="mt-1 whitespace-pre-line text-xs text-amber-300">{message[item.projectId]}</p>}
            </div>
            <div className="flex gap-2">
              <button className="btn" onClick={() => setDetailId(item.projectId)}>상세</button>
              {installedIds.has(item.projectId)
                ? <span className="btn opacity-60">설치됨</span>
                : item.latestFileId == null
                  ? <span className="btn opacity-60">이 버전 파일 없음</span>
                  : !item.downloadable
                    // 작성자가 외부 앱의 직접 설치를 막은 경우. CurseForge 앱이 있으면 그쪽으로 넘길 수 있다.
                    ? <a className="btn" href={curseforgeInstallUrl(item.projectId, item.latestFileId)} {...curseforgeLinkAttrs()}
                         title="작성자가 외부 배포를 막았습니다. CurseForge 앱이 설치돼 있으면 그 앱으로 설치합니다">
                        CurseForge 앱으로 설치
                      </a>
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

      {detailId != null && envId != null && (
        <AddonDetailModal
          envId={envId}
          projectId={detailId}
          canInstall={desktop && !!folder.handle}
          installedIds={installedIds}
          onInstall={(projectId, fileId) => installFile(projectId, fileId, () => undefined)}
          onClose={() => setDetailId(null)}
        />
      )}
    </div>
  )
}
