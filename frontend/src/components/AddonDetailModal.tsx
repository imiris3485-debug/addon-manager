import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { curseforgeInstallUrl, curseforgeLinkAttrs } from '../lib/curseforgeLink'
import { formatAgo, formatBytes, formatCount, formatDate } from '../lib/format'
import { Changelog } from './Changelog'
import { ReleaseBadge } from './ReleaseBadge'

interface Props {
  envId: number
  projectId: number
  /** 데스크톱 앱에서만 설치할 수 있다(웹 브라우저는 폴더를 다루지 못한다) */
  canInstall: boolean
  /** 이미 설치된 프로젝트 ID 들 */
  installedIds: Set<number>
  /** 파일을 설치하고 결과 안내 글을 돌려준다. 실패하면 던진다. */
  onInstall: (projectId: number, fileId: number) => Promise<string>
  onClose: () => void
}

type Tab = 'overview' | 'files'

export function AddonDetailModal({ envId, projectId, canInstall, installedIds, onInstall, onClose }: Props) {
  const [tab, setTab] = useState<Tab>('overview')
  const [openChangelog, setOpenChangelog] = useState<number | null>(null)
  const [messages, setMessages] = useState<Record<number, string>>({})
  const [busyFile, setBusyFile] = useState<number | null>(null)

  const { data: detail, isLoading, error } = useQuery({
    queryKey: ['addon-detail', envId, projectId],
    queryFn: () => api.addonDetail(envId, projectId),
    staleTime: 5 * 60 * 1000,
  })
  // 파일 목록은 "파일" 탭을 열 때 불러온다
  const { data: files, isLoading: filesLoading, error: filesError } = useQuery({
    queryKey: ['addon-files', envId, projectId],
    queryFn: () => api.addonFiles(envId, projectId),
    enabled: tab === 'files',
    staleTime: 5 * 60 * 1000,
  })

  // Esc 로 닫기
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const install = async (fileId: number) => {
    setBusyFile(fileId)
    setMessages((m) => ({ ...m, [fileId]: '설치 중...' }))
    try {
      const text = await onInstall(projectId, fileId)
      setMessages((m) => ({ ...m, [fileId]: text }))
    } catch (e) {
      setMessages((m) => ({ ...m, [fileId]: `실패: ${(e as Error).message}` }))
    } finally {
      setBusyFile(null)
    }
  }

  const installed = installedIds.has(projectId)

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/70 p-4" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-label="애드온 상세"
           className="card my-6 w-full max-w-3xl space-y-4" onClick={(e) => e.stopPropagation()}>
        {isLoading && <p className="text-slate-400">불러오는 중...</p>}
        {error && <p className="text-rose-400">{(error as Error).message}</p>}

        {detail && (
          <>
            <div className="flex items-start gap-3">
              {detail.logoUrl
                ? <img src={detail.logoUrl} alt="" referrerPolicy="no-referrer" className="h-16 w-16 rounded" />
                : <div className="h-16 w-16 rounded bg-slate-800" />}
              <div className="min-w-0 flex-1">
                <h2 className="text-lg font-bold">{detail.name} {installed && <span className="text-sm font-normal text-emerald-400">· 설치됨</span>}</h2>
                <p className="text-sm text-slate-400">
                  {detail.authors.map((a, i) => (
                    <span key={a.name}>{i > 0 && ', '}{a.url
                      ? <a className="hover:underline" href={a.url} target="_blank" rel="noreferrer">{a.name}</a> : a.name}</span>
                  ))}
                </p>
                <p className="text-xs text-slate-500">
                  다운로드 {formatCount(detail.downloadCount)} · 👍 {formatCount(detail.thumbsUpCount)}
                  {detail.lastUpdated && <> · 갱신 {formatDate(detail.lastUpdated)} ({formatAgo(detail.lastUpdated)})</>}
                  {detail.license && <> · {detail.license}</>}
                </p>
              </div>
              <button className="btn" onClick={onClose} aria-label="닫기">닫기</button>
            </div>

            {detail.warning && (
              <p className="rounded-md border border-amber-600/40 bg-amber-500/10 p-2 text-sm text-amber-200">⚠ {detail.warning}</p>
            )}

            <div className="flex gap-1 border-b border-slate-800">
              {(['overview', 'files'] as const).map((t) => (
                <button key={t} onClick={() => setTab(t)}
                        className={`px-3 py-1.5 text-sm font-medium ${tab === t ? 'border-b-2 border-indigo-400 text-white' : 'text-slate-400 hover:text-white'}`}>
                  {t === 'overview' ? '개요' : '파일·버전'}
                </button>
              ))}
            </div>

            {tab === 'overview' && (
              <div className="space-y-4">
                {detail.summary && <p className="text-sm text-slate-200">{detail.summary}</p>}

                {detail.categories.length > 0 && (
                  <div className="flex flex-wrap gap-1">
                    {detail.categories.map((c) => <span key={c} className="rounded bg-slate-800 px-2 py-0.5 text-xs text-slate-300">{c}</span>)}
                  </div>
                )}

                {detail.screenshots.length > 0 && (
                  <div className="flex gap-2 overflow-x-auto pb-1">
                    {detail.screenshots.map((s) => (
                      <a key={s.url} href={s.url} target="_blank" rel="noreferrer" title={s.title ?? ''} className="shrink-0">
                        <img src={s.thumbnailUrl ?? s.url} alt={s.title ?? '스크린샷'} loading="lazy" referrerPolicy="no-referrer"
                             className="h-28 rounded border border-slate-800" />
                      </a>
                    ))}
                  </div>
                )}

                {detail.description && (
                  // 서버가 HTML 을 지운 글자만 준다. 텍스트로만 그려서 스크립트가 실행될 수 없다.
                  <div className="max-h-80 overflow-auto whitespace-pre-wrap rounded-md bg-slate-900/70 p-3 text-sm text-slate-300">
                    {detail.description}
                  </div>
                )}

                <div className="flex flex-wrap gap-2 text-sm">
                  {detail.websiteUrl && <a className="btn" href={detail.websiteUrl} target="_blank" rel="noreferrer">CurseForge</a>}
                  {detail.sourceUrl && <a className="btn" href={detail.sourceUrl} target="_blank" rel="noreferrer">소스 코드</a>}
                  {detail.issuesUrl && <a className="btn" href={detail.issuesUrl} target="_blank" rel="noreferrer">문제 신고</a>}
                  {detail.wikiUrl && <a className="btn" href={detail.wikiUrl} target="_blank" rel="noreferrer">위키</a>}
                  {!detail.downloadable && (
                    <a className="btn btn-primary" href={curseforgeInstallUrl(projectId)} {...curseforgeLinkAttrs()}
                       title="이 PC 에 CurseForge 앱이 있어야 합니다. 설치 위치는 CurseForge 앱의 설정을 따릅니다.">
                      CurseForge 앱으로 설치
                    </a>
                  )}
                </div>
                {!detail.downloadable && (
                  <p className="text-xs text-slate-500">
                    작성자가 외부 앱의 직접 설치를 허용하지 않은 애드온입니다. CurseForge 앱이나 웹사이트에서 설치한 뒤,
                    "설치된 애드온"에서 다시 스캔하면 목록에 나타납니다.
                  </p>
                )}
              </div>
            )}

            {tab === 'files' && (
              <div className="space-y-2">
                {filesLoading && <p className="text-sm text-slate-400">파일 목록을 불러오는 중...</p>}
                {filesError && <p className="text-sm text-rose-400">{(filesError as Error).message}</p>}
                {files && files.length === 0 && <p className="text-sm text-slate-400">이 게임 버전용 파일이 없습니다.</p>}
                {files?.map((f) => (
                  <div key={f.fileId} className="rounded-md border border-slate-800 p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <ReleaseBadge type={f.releaseType} />
                      <span className="min-w-0 flex-1 truncate text-sm font-medium">{f.displayName || f.fileName}</span>
                      <span className="text-xs text-slate-500">
                        {formatDate(f.fileDate)} · {formatBytes(f.fileLength)} · 다운로드 {formatCount(f.downloadCount)}
                      </span>
                    </div>
                    {f.folders.length > 0 && <p className="mt-1 text-xs text-slate-500">설치 폴더: {f.folders.join(', ')}</p>}
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <button className="btn" onClick={() => setOpenChangelog(openChangelog === f.fileId ? null : f.fileId)}>
                        {openChangelog === f.fileId ? '변경 내역 접기' : '변경 내역'}
                      </button>
                      {canInstall && detail.downloadable && (
                        <button className="btn btn-primary" disabled={busyFile !== null} onClick={() => install(f.fileId)}>
                          {installed ? '이 버전으로 설치' : '설치'}
                        </button>
                      )}
                      {!canInstall && <span className="text-xs text-slate-500">설치는 데스크톱 앱에서 할 수 있습니다</span>}
                    </div>
                    {messages[f.fileId] && <p className="mt-1 whitespace-pre-line text-xs text-amber-300">{messages[f.fileId]}</p>}
                    {openChangelog === f.fileId && <div className="mt-2"><Changelog projectId={projectId} fileId={f.fileId} /></div>}
                  </div>
                ))}
                {files && files.some((f) => f.releaseType !== 'RELEASE') && (
                  <p className="text-xs text-slate-500">베타·알파는 시험 버전입니다. 평소에는 정식 파일을 쓰는 것이 안전합니다.</p>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
