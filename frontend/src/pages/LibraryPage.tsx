import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useEnv } from '../lib/EnvContext'
import { formatAgo, formatBytes } from '../lib/format'
import { removeFolder } from '../lib/installer'
import { forgetInstalled, installFromLibrary, libraryState, loadInstalledHashes, reportInstalls, type LibraryState } from '../lib/library'
import { isDesktop } from '../lib/nativeFs'
import { scanFolders } from '../lib/scanner'
import type { LibraryAddon, LocalFolder } from '../lib/types'
import { useFolder } from '../lib/useFolder'

type Filter = 'ALL' | 'NOT_INSTALLED' | 'INSTALLED' | 'UPDATE_AVAILABLE'

const FILTER_LABEL: Record<Filter, string> = {
  ALL: '전체', NOT_INSTALLED: '설치 안 함', INSTALLED: '설치됨', UPDATE_AVAILABLE: '업데이트 가능',
}

const STATE_BADGE: Record<Exclude<LibraryState, 'NOT_INSTALLED'>, { label: string; cls: string }> = {
  UP_TO_DATE: { label: '최신', cls: 'bg-emerald-500/15 text-emerald-300' },
  UPDATE_AVAILABLE: { label: '업데이트 가능', cls: 'bg-amber-500/15 text-amber-300' },
  UNKNOWN: { label: '설치됨(확인 불가)', cls: 'bg-slate-500/20 text-slate-300' },
}

export default function LibraryPage() {
  const { current } = useEnv()
  const desktop = isDesktop()
  const envId = desktop ? current?.id ?? null : null
  const qc = useQueryClient()
  const folder = useFolder(envId)

  const { data: library, isLoading, error, refetch: refetchLibrary } = useQuery({ queryKey: ['library'], queryFn: api.library })
  // 이 PC 의 AddOns 폴더를 읽어 설치 상태를 판단한다(앱에서만). 서버와 통신하지 않는다.
  const { data: local, refetch: refetchLocal, isFetching: scanning } = useQuery<LocalFolder[]>({
    queryKey: ['local-folders', envId],
    queryFn: () => scanFolders(folder.handle!),
    enabled: desktop && envId != null && !!folder.handle,
    staleTime: 0,
  })

  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<Filter>('ALL')
  const [busy, setBusy] = useState<string | null>(null)          // 작업 중인 항목 이름 또는 '*'
  const [message, setMessage] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState<string | null>(null)

  const localByName = useMemo(() => new Map((local ?? []).map((l) => [l.name.toLowerCase(), l])), [local])
  const hashes = envId != null ? loadInstalledHashes(envId) : {}
  const libraryNames = useMemo(() => new Set((library ?? []).map((a) => a.name.toLowerCase())), [library])

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (library ?? [])
      .map((item) => ({ item, state: desktop ? libraryState(item, localByName.get(item.name.toLowerCase()), hashes) : ('NOT_INSTALLED' as LibraryState) }))
      .filter(({ item }) => !q || item.name.toLowerCase().includes(q))
      .filter(({ state }) => {
        if (!desktop || filter === 'ALL') return true
        if (filter === 'NOT_INSTALLED') return state === 'NOT_INSTALLED'
        if (filter === 'INSTALLED') return state !== 'NOT_INSTALLED'
        return state === 'UPDATE_AVAILABLE'
      })
    // hashes 는 렌더마다 localStorage 에서 읽는 값이라 local 이 바뀔 때 함께 다시 계산한다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [library, localByName, query, filter, desktop, local])

  const outdated = rows.filter((r) => r.state === 'UPDATE_AVAILABLE').map((r) => r.item)
  const unmanaged = useMemo(() => (local ?? []).filter((l) => !libraryNames.has(l.name.toLowerCase())), [local, libraryNames])

  const install = async (item: LibraryAddon) => {
    if (envId == null || !library) return
    const say = (text: string) => setMessage((m) => ({ ...m, [item.name]: text }))
    setBusy(item.name)
    say('설치 중...')
    try {
      const root = await folder.requireAccess()
      const installedNames = new Set(localByName.keys())
      const result = await installFromLibrary(envId, root, item, library, installedNames, say)
      const extra = result.installed.filter((n) => n !== item.name)
      const lines = ['설치 완료']
      if (extra.length > 0) lines.push(`함께 설치했습니다: ${extra.join(', ')}`)
      if (result.missing.length > 0) lines.push(`⚠ 자료실에 없어 설치하지 못한 필요한 애드온: ${result.missing.join(', ')}`)
      say(lines.join('\n'))
      await refetchLocal()
    } catch (e) {
      say(`실패: ${(e as Error).message}`)
    } finally {
      setBusy(null)
    }
  }

  const updateAll = async () => {
    if (envId == null || !library || outdated.length === 0) return
    setNotice(null)
    setBusy('*')
    let failed = 0
    try {
      const root = await folder.requireAccess()
      for (const [i, item] of outdated.entries()) {
        setMessage((m) => ({ ...m, [item.name]: `업데이트 중 (${i + 1}/${outdated.length})...` }))
        try {
          await installFromLibrary(envId, root, item, library, new Set(localByName.keys()), () => undefined)
          setMessage((m) => ({ ...m, [item.name]: '업데이트 완료' }))
        } catch (e) {
          failed++
          setMessage((m) => ({ ...m, [item.name]: `실패: ${(e as Error).message}` }))
        }
      }
      if (failed > 0) setNotice(`${failed}개를 업데이트하지 못했습니다. 목록의 오류 메시지를 확인하세요.`)
      await refetchLocal()
    } catch (e) {
      setNotice((e as Error).message)
    } finally {
      setBusy(null)
    }
  }

  /** 자료실에 없는 폴더 삭제. 이 앱이 관리하지 않는 폴더라 폴더만 지운다. */
  const removeUnmanaged = async (name: string) => {
    if (envId == null) return
    if (!confirm(`'${name}' 폴더를 삭제할까요?\n자료실에 없는 폴더라 되돌릴 수 없고, 다른 애드온이 이 폴더를 쓰고 있을 수 있습니다.`)) return
    setNotice(null)
    try {
      await removeFolder(await folder.requireAccess(), name)
      forgetInstalled(envId, name)
      await reportInstalls(envId, [{ addonName: name, action: 'REMOVED' }])
      await refetchLocal()
    } catch (e) {
      setNotice((e as Error).message)
    }
  }

  /** 자료실에서 설치한 애드온 삭제. 폴더를 지우고 설치 기록·서버 이력에도 반영한다. */
  const removeInstalled = async (item: LibraryAddon) => {
    if (envId == null) return
    if (!confirm(`'${item.name}' 폴더를 삭제할까요?
다른 애드온이 이 애드온을 필요로 할 수 있습니다.`)) return
    setNotice(null)
    setBusy(item.name)
    try {
      await removeFolder(await folder.requireAccess(), item.name)
      forgetInstalled(envId, item.name)
      await reportInstalls(envId, [{ addonName: item.name, action: 'REMOVED' }])
      setMessage((m) => ({ ...m, [item.name]: '삭제했습니다' }))
      await refetchLocal()
    } catch (e) {
      setMessage((m) => ({ ...m, [item.name]: `실패: ${(e as Error).message}` }))
    } finally {
      setBusy(null)
    }
  }

  const refresh = async () => {
    setNotice(null)
    await Promise.all([refetchLibrary(), desktop && envId != null && folder.handle ? refetchLocal() : Promise.resolve()])
    qc.invalidateQueries({ queryKey: ['library'] })
  }

  const missingFor = (item: LibraryAddon) =>
    item.requires.filter((r) => !libraryNames.has(r.toLowerCase()) && !localByName.has(r.toLowerCase()))

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="text-xl font-bold">애드온 자료실</h1>
        <span className="text-sm text-slate-400">{library ? `${library.length}개` : ''}</span>
        <div className="ml-auto flex gap-2">
          <button className="btn" onClick={refresh} disabled={busy !== null || scanning}>{scanning ? '확인하는 중...' : '새로고침'}</button>
          {desktop && (
            <button className="btn btn-primary" onClick={updateAll} disabled={busy !== null || outdated.length === 0}>
              모두 업데이트{outdated.length > 0 ? ` (${outdated.length})` : ''}
            </button>
          )}
        </div>
      </div>

      {!desktop && (
        <p className="rounded-md border border-indigo-500/30 bg-indigo-500/10 p-3 text-sm text-indigo-200">
          웹에서는 자료실 목록만 볼 수 있습니다. 설치·업데이트는 <Link className="underline" to="/download">데스크톱 앱</Link>에서 하세요.
        </p>
      )}

      <div className="flex flex-wrap gap-2">
        <input className="input min-w-48 flex-1" placeholder="애드온 이름 검색" value={query} onChange={(e) => setQuery(e.target.value)} />
        {desktop && (
          <select className="input !w-auto" aria-label="상태" value={filter} onChange={(e) => setFilter(e.target.value as Filter)}>
            {(Object.keys(FILTER_LABEL) as Filter[]).map((f) => <option key={f} value={f}>{FILTER_LABEL[f]}</option>)}
          </select>
        )}
      </div>

      {notice && <p className="text-sm text-rose-400">{notice}</p>}
      {isLoading && <p className="text-slate-400">불러오는 중...</p>}
      {error && <p className="text-sm text-rose-400">{(error as Error).message}</p>}
      {library && library.length === 0 && <div className="card text-slate-400">아직 자료실에 올라온 애드온이 없습니다.</div>}
      {library && library.length > 0 && rows.length === 0 && <p className="text-slate-400">조건에 맞는 애드온이 없습니다.</p>}

      <ul className="space-y-2">
        {rows.map(({ item, state }) => {
          const missing = desktop ? missingFor(item) : []
          return (
            <li key={item.id} className="card space-y-1">
              <div className="flex flex-wrap items-center gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{item.name}</span>
                    {item.version && <span className="text-xs text-slate-400">v{item.version}</span>}
                    {desktop && state !== 'NOT_INSTALLED' && (
                      <span className={`rounded px-1.5 py-0.5 text-xs ${STATE_BADGE[state].cls}`}>{STATE_BADGE[state].label}</span>
                    )}
                  </div>
                  <div className="text-xs text-slate-500">
                    {formatBytes(item.fileSize)}{item.updatedAt && <> · {formatAgo(item.updatedAt)} 갱신</>}
                    {item.requires.length > 0 && <> · 함께 설치: {item.requires.join(', ')}</>}
                  </div>
                </div>
                {desktop && (
                  <div className="flex gap-2">
                    <button className="btn btn-primary" disabled={busy !== null || !folder.handle || state === 'UP_TO_DATE'} onClick={() => install(item)}>
                      {state === 'UPDATE_AVAILABLE' ? '업데이트' : state === 'UP_TO_DATE' ? '최신' : state === 'UNKNOWN' ? '자료실 것으로 설치' : '설치'}
                    </button>
                    {state !== 'NOT_INSTALLED' && (
                      <button className="btn btn-danger" disabled={busy !== null || !folder.handle} onClick={() => removeInstalled(item)}>삭제</button>
                    )}
                  </div>
                )}
              </div>
              {item.note && <p className="text-xs text-slate-400">메모: {item.note}</p>}
              {missing.length > 0 && (
                <p className="text-xs text-amber-300">⚠ 함께 필요한 {missing.join(', ')} 이(가) 자료실에 없습니다. 관리자에게 알려 주세요.</p>
              )}
              {message[item.name] && <p className="whitespace-pre-line text-xs text-amber-300">{message[item.name]}</p>}
            </li>
          )
        })}
      </ul>

      {desktop && unmanaged.length > 0 && (
        <details className="card">
          <summary className="cursor-pointer text-sm text-slate-300">자료실에 없는 폴더 {unmanaged.length}개</summary>
          <p className="mt-2 text-xs text-slate-500">
            내 AddOns 폴더에 있지만 자료실 항목이 아닌 애드온입니다. 이 앱이 업데이트하지 않습니다.
            다른 애드온이 쓰는 폴더일 수 있으니 무엇인지 확인한 뒤 삭제하세요.
          </p>
          <ul className="mt-2 space-y-1">
            {unmanaged.map((f) => (
              <li key={f.name} className="flex items-center gap-2 text-xs text-slate-300">
                <span className="min-w-0 flex-1 truncate">{f.name}{f.version && <span className="text-slate-500"> v{f.version}</span>}</span>
                <button className="btn btn-danger !px-2 !py-0.5" disabled={busy !== null} onClick={() => removeUnmanaged(f.name)}>삭제</button>
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  )
}
