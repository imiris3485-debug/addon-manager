import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useEnv } from '../lib/EnvContext'
import { formatAgo, formatBytes } from '../lib/format'
import { removeFolder } from '../lib/installer'
import { forgetInstalled, installFromLibrary, libraryState, reportInstalls, satisfiedNames, type LibraryState } from '../lib/library'
import { dependencyNames, lc, transitiveDeps } from '../lib/modules'
import { useAutoSnapshot } from '../lib/useAutoSnapshot'
import { useInstalledHashes } from '../lib/useInstalledHashes'
import { isDesktop } from '../lib/nativeFs'
import { scanFolders } from '../lib/scanner'
import type { LibraryAddon, LocalFolder } from '../lib/types'
import { useFolder } from '../lib/useFolder'

type View = 'LIBRARY' | 'INSTALLED'
type Filter = 'ALL' | 'NOT_INSTALLED' | 'INSTALLED' | 'UPDATE_AVAILABLE'

const FILTER_LABEL: Record<Filter, string> = {
  ALL: '전체', NOT_INSTALLED: '새 의뢰(설치 안 함)', INSTALLED: '설치됨', UPDATE_AVAILABLE: '갱신할 수 있음',
}

/**
 * 목록의 한 줄. state 가 null 이면 자료실에 없는 폴더(내 설치 목록에서만).
 * state 는 함께 설치되는 애드온까지 합친 묶음의 상태이고, ownState 는 이 애드온 하나만의 상태다.
 */
interface Entry {
  key: string
  name: string
  version: string | null
  item?: LibraryAddon
  local?: LocalFolder
  state: LibraryState | null
  ownState: LibraryState | null
  /** 이 애드온과 함께 설치되는 자료실 애드온(의존성의 의존성까지) */
  deps: LibraryAddon[]
}

/**
 * 묶음의 상태. 부모는 최신이어도 함께 설치되는 애드온이 오래됐거나 없으면 "갱신할 수 있음"이다
 * (부모를 갱신하면 함께 설치되는 것도 최신이 되기 때문이다).
 */
function groupState(own: LibraryState, deps: LibraryAddon[], localByName: Map<string, LocalFolder>, hashes: Record<string, string>): LibraryState {
  if (own === 'NOT_INSTALLED' || own === 'UPDATE_AVAILABLE') return own
  for (const d of deps) {
    const s = libraryState(d, localByName.get(lc(d.name)), hashes)
    if (s === 'UPDATE_AVAILABLE' || s === 'NOT_INSTALLED') return 'UPDATE_AVAILABLE'
  }
  return own
}

export default function LibraryPage() {
  const { current } = useEnv()
  const desktop = isDesktop()
  const envId = desktop ? current?.id ?? null : null
  const qc = useQueryClient()
  const folder = useFolder(envId)

  const { data: library, isLoading, error, refetch: refetchLibrary } = useQuery({ queryKey: ['library'], queryFn: api.library })
  // 이 PC 의 AddOns 폴더를 읽어 설치 상태를 판단한다(앱에서만). 서버와 통신하지 않는다.
  const { data: local, refetch: refetchLocal, isFetching: scanningFolders, dataUpdatedAt } = useQuery<LocalFolder[]>({
    queryKey: ['local-folders', envId],
    queryFn: () => scanFolders(folder.handle!),
    enabled: desktop && envId != null && !!folder.handle,
    staleTime: 0,
  })

  const [view, setView] = useState<View>('LIBRARY')
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<Filter>('ALL')
  const [busy, setBusy] = useState<string | null>(null)          // 작업 중인 항목 이름 또는 '*'
  const [message, setMessage] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState<string | null>(null)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)   // 오른쪽 쪽에 펼친 항목

  const localByName = useMemo(() => new Map((local ?? []).map((l) => [l.name.toLowerCase(), l])), [local])
  // 폴더의 실제 내용 해시(자료실과 정확히 비교). 설치 기록·버전은 실제 해시를 못 구했을 때의 대비책이다.
  const { hashes, hashing } = useInstalledHashes(envId, folder.handle, local, library, dataUpdatedAt)
  const scanning = scanningFolders || hashing
  // 폴더를 읽을 때마다 내 애드온 목록이 바뀌었으면 서버 보관함에 자동으로 남긴다(실패해도 화면에는 영향 없음)
  useAutoSnapshot(envId, local, library)
  const libraryNames = useMemo(() => new Set((library ?? []).map((a) => a.name.toLowerCase())), [library])

  const libraryByName = useMemo(() => new Map((library ?? []).map((a) => [a.name.toLowerCase(), a])), [library])
  // 다른 애드온의 의존성으로 쓰이는 애드온: 목록에서는 부모 아래로 묶어 숨긴다(검색할 때는 모두 보인다)
  const hiddenDeps = useMemo(() => dependencyNames(library ?? []), [library])

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (library ?? [])
      .filter((item) => !!q || !hiddenDeps.has(lc(item.name)))
      .map((item) => {
        const deps = transitiveDeps(item, libraryByName)
        const own = desktop ? libraryState(item, localByName.get(lc(item.name)), hashes) : ('NOT_INSTALLED' as LibraryState)
        return { item, deps, ownState: own, state: desktop ? groupState(own, deps, localByName, hashes) : own }
      })
      .filter(({ item }) => !q || item.name.toLowerCase().includes(q))
      .filter(({ state }) => {
        if (!desktop || filter === 'ALL') return true
        if (filter === 'NOT_INSTALLED') return state === 'NOT_INSTALLED'
        if (filter === 'INSTALLED') return state !== 'NOT_INSTALLED'
        return state === 'UPDATE_AVAILABLE'
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [library, libraryByName, hiddenDeps, localByName, query, filter, desktop, local, hashes])

  // 부모(묶음) 기준 상태. 필터·검색과 무관하다. "모두 갱신"은 부모를 갱신하면서 함께 설치되는 것도 최신으로 올린다.
  const topItems = useMemo(() => (library ?? []).filter((a) => !hiddenDeps.has(lc(a.name))).map((item) => {
    const deps = transitiveDeps(item, libraryByName)
    const own = libraryState(item, localByName.get(lc(item.name)), hashes)
    return { item, state: groupState(own, deps, localByName, hashes) }
  }), [library, libraryByName, hiddenDeps, localByName, hashes])
  const outdated = topItems.filter((r) => r.state === 'UPDATE_AVAILABLE').map((r) => r.item)
  // 내 PC 의 AddOns 폴더에 있는 모든 애드온(자료실에 있든 없든). 개별 업데이트·삭제는 여기서 한다.
  const installedRows = useMemo(() => {
    const q = query.trim().toLowerCase()
    // 설치된 부모 애드온이 함께 설치하는 폴더들(부모 아래로 묶어 숨긴다. 검색할 때는 모두 보인다)
    const covered = new Set<string>()
    for (const l of local ?? []) {
      const item = libraryByName.get(lc(l.name))
      if (item && !hiddenDeps.has(lc(item.name))) for (const d of transitiveDeps(item, libraryByName)) covered.add(lc(d.name))
    }
    return (local ?? [])
      .filter((l) => !q ? !covered.has(lc(l.name)) : l.name.toLowerCase().includes(q))
      .map((l) => {
        const item = libraryByName.get(lc(l.name))
        const deps = item ? transitiveDeps(item, libraryByName) : []
        const own = item ? libraryState(item, l, hashes) : null
        return { folder: l, item, deps, ownState: own, state: own ? groupState(own, deps, localByName, hashes) : null }
      })
      .sort((a, b) => a.folder.name.localeCompare(b.folder.name))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [local, libraryByName, hiddenDeps, localByName, query, hashes])

  const install = async (item: LibraryAddon) => {
    if (envId == null || !library) return
    const say = (text: string) => setMessage((m) => ({ ...m, [item.name]: text }))
    setBusy(item.name)
    say('설치 중...')
    try {
      const root = await folder.requireAccess()
      // 필요한 애드온이 설치돼 있어도 자료실보다 오래됐으면 함께 업데이트한다
      const result = await installFromLibrary(envId, root, item, library, satisfiedNames(library, local ?? [], hashes), say)
      const extra = result.installed.filter((n) => n !== item.name)
      const lines = ['설치 완료']
      if (extra.length > 0) lines.push(`함께 설치·업데이트했습니다: ${extra.join(', ')}`)
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
      const satisfied = satisfiedNames(library, local ?? [], hashes)
      for (const [i, item] of outdated.entries()) {
        // 앞 항목을 업데이트하면서 함께 최신이 된 것은 다시 받지 않는다
        if (satisfied.has(item.name.toLowerCase())) {
          setMessage((m) => ({ ...m, [item.name]: '업데이트 완료(함께 업데이트됨)' }))
          continue
        }
        setMessage((m) => ({ ...m, [item.name]: `업데이트 중 (${i + 1}/${outdated.length})...` }))
        try {
          const r = await installFromLibrary(envId, root, item, library, satisfied, () => undefined)
          for (const n of r.installed) satisfied.add(n.toLowerCase())
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

  // 왼쪽 쪽(의뢰 목록)에 보일 항목. 자료실 탭은 자료실 애드온, 설치 목록 탭은 이 PC 폴더 전부.
  const entries: Entry[] = view === 'LIBRARY'
    ? rows.map(({ item, state, ownState, deps }) => ({ key: `lib:${item.id}`, name: item.name, version: item.version, item, local: localByName.get(item.name.toLowerCase()), state, ownState, deps }))
    : installedRows.map(({ folder: f, item, state, ownState, deps }) => ({ key: `loc:${f.name}`, name: f.name, version: f.version, item, local: f, state, ownState, deps }))
  const selected = entries.find((e) => e.key === selectedKey) ?? entries[0]

  // 상단 요약(필터와 무관하게 자료실 전체 기준)
  const summary = useMemo(() => {
    let fresh = 0, update = 0, done = 0
    for (const { state } of topItems) {
      if (state === 'NOT_INSTALLED') fresh++
      else if (state === 'UPDATE_AVAILABLE') update++
      else done++
    }
    return { fresh, update, done }
  }, [topItems])

  const tabCls = (active: boolean) =>
    `rounded-t px-4 py-2 text-sm transition ${active ? 'bg-[#e9d8b0] font-bold text-[#3b2412] shadow-[inset_0_2px_0_#fff3cf]' : 'text-slate-300 hover:text-white'}`

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end gap-2">
        {desktop && (
          <div className="flex gap-1">
            <button className={tabCls(view === 'LIBRARY')} onClick={() => setView('LIBRARY')}>자료실 {library ? library.length : ''}</button>
            <button className={tabCls(view === 'INSTALLED')} onClick={() => setView('INSTALLED')}>내 설치 목록 {local ? local.length : ''}</button>
          </div>
        )}
        <div className="ml-auto flex gap-2 pb-1">
          <button className="btn" onClick={refresh} disabled={busy !== null || scanning}>{scanning ? '확인하는 중...' : '새로고침'}</button>
          {desktop && (
            <button className="btn btn-primary" onClick={updateAll} disabled={busy !== null || summary.update === 0}>
              모두 갱신{summary.update > 0 ? ` (${summary.update})` : ''}
            </button>
          )}
        </div>
      </div>

      {!desktop && (
        <p className="rounded-md border border-indigo-500/30 bg-indigo-500/10 p-3 text-sm text-indigo-200">
          웹에서는 자료실 목록만 볼 수 있습니다. 설치·업데이트는 <Link className="underline" to="/download">데스크톱 앱</Link>에서 하세요.
        </p>
      )}
      {notice && <p className="text-sm text-rose-400">{notice}</p>}
      {error && <p className="text-sm text-rose-400">{(error as Error).message}</p>}

      <section className="book">
        {/* 왼쪽 쪽: 의뢰 목록 */}
        <div className="page-left flex w-full flex-col gap-3 p-5 md:w-[392px] md:shrink-0">
          <div className="flex flex-wrap items-baseline gap-x-3">
            <h1 className="ink text-2xl font-bold">{view === 'LIBRARY' ? '애드온 의뢰' : '내 설치 목록'}</h1>
            {desktop && view === 'LIBRARY' && <span className="ink-soft text-xs">새 의뢰 {summary.fresh} · 갱신 {summary.update} · 완료 {summary.done}</span>}
          </div>
          <input className="parch-input" placeholder="애드온 이름 찾기" value={query} onChange={(e) => setQuery(e.target.value)} />
          {desktop && view === 'LIBRARY' && (
            <select className="parch-input" aria-label="상태" value={filter} onChange={(e) => setFilter(e.target.value as Filter)}>
              {(Object.keys(FILTER_LABEL) as Filter[]).map((f) => <option key={f} value={f}>{FILTER_LABEL[f]}</option>)}
            </select>
          )}

          {isLoading && <p className="ink-soft text-sm">불러오는 중...</p>}
          {view === 'LIBRARY' && library && library.length === 0 && <p className="ink-soft text-sm">아직 자료실에 올라온 애드온이 없습니다.</p>}
          {entries.length === 0 && !isLoading && (view === 'INSTALLED' ? !!local : !!library && library.length > 0) && (
            <p className="ink-soft text-sm">{view === 'INSTALLED' && !query ? '이 AddOns 폴더에서 애드온을 찾지 못했습니다.' : '조건에 맞는 애드온이 없습니다.'}</p>
          )}

          <ul className="flex max-h-[46vh] flex-col gap-1 overflow-y-auto md:max-h-[54vh]">
            {entries.map((e) => {
              const m = marker(e, desktop)
              return (
                <li key={e.key}>
                  <button className="quest-row" aria-current={e.key === selected?.key} onClick={() => setSelectedKey(e.key)}>
                    <span className={`quest-mark ${m.cls}`} aria-hidden="true">{m.ch}</span>
                    <span className="min-w-0 flex-1">
                      <span className="ink block truncate font-[family-name:var(--font-display)] text-lg font-bold">{e.name}</span>
                      <span className="ink-soft block truncate text-xs">{[e.version && `v${e.version}`, m.label].filter(Boolean).join(' · ')}</span>
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>

          {desktop && (
            <p className="ink-soft mt-auto text-xs leading-relaxed">
              <b className="text-[#b07a00]">!</b> 새 의뢰(설치 안 함) &nbsp; <b className="text-[#b07a00]">?</b> 갱신할 수 있음 &nbsp; <b>✓</b> 완료(최신)
            </p>
          )}
        </div>

        {/* 오른쪽 쪽: 고른 의뢰의 내용 */}
        <article className="page-right flex min-w-0 flex-1 flex-col gap-4 p-6 md:p-8">
          {!selected ? (
            <p className="ink-soft">왼쪽에서 애드온을 골라 보세요.</p>
          ) : (
            <Detail
              entry={selected}
              desktop={desktop}
              message={message[selected.item?.name ?? selected.name]}
              busy={busy !== null}
              canAct={!!folder.handle}
              libraryByName={libraryByName}
              localByName={localByName}
              hashes={hashes}
              missing={selected.item && desktop ? missingFor(selected.item) : []}
              onInstall={() => selected.item && install(selected.item)}
              onRemove={() => (selected.item ? removeInstalled(selected.item) : removeUnmanaged(selected.name))}
            />
          )}
        </article>
      </section>
    </div>
  )
}

/** 목록 앞의 의뢰 표시. 와우의 노란 느낌표(새 의뢰)·물음표(갱신)·회색 체크(완료)를 따른다. */
function marker(e: Entry, desktop: boolean): { ch: string; cls: 'gold' | 'grey'; label: string } {
  if (!desktop) return { ch: '◆', cls: 'grey', label: '' }
  switch (e.state) {
    case 'NOT_INSTALLED': return { ch: '!', cls: 'gold', label: '새 의뢰' }
    case 'UPDATE_AVAILABLE': return { ch: '?', cls: 'gold', label: '갱신할 수 있음' }
    case 'UP_TO_DATE': return { ch: '✓', cls: 'grey', label: '완료' }
    case 'UNKNOWN': return { ch: '✓', cls: 'grey', label: '설치됨(확인 불가)' }
    default: return { ch: '·', cls: 'grey', label: '자료실에 없음' }
  }
}

/** 아이템 품질 색: 최신=초록, 갱신=주황, 그 밖=회색 */
const RING: Record<string, { ring: string; glow: string; text: string }> = {
  UP_TO_DATE: { ring: '#2e9a2e', glow: 'rgba(46,154,46,.5)', text: '#17661a' },
  UPDATE_AVAILABLE: { ring: '#d9822b', glow: 'rgba(217,130,43,.55)', text: '#9a4a00' },
  OTHER: { ring: '#8b7b63', glow: 'rgba(0,0,0,0)', text: '#5f5140' },
}

function Detail({ entry, desktop, message, busy, canAct, libraryByName, localByName, hashes, missing, onInstall, onRemove }: {
  entry: Entry
  desktop: boolean
  message: string | undefined
  busy: boolean
  canAct: boolean
  libraryByName: Map<string, LibraryAddon>
  localByName: Map<string, LocalFolder>
  hashes: Record<string, string>
  missing: string[]
  onInstall: () => void
  onRemove: () => void
}) {
  const { item, local, state, ownState, deps } = entry
  const look = RING[state ?? 'OTHER'] ?? RING.OTHER
  const m = marker(entry, desktop)
  const primary = state === 'NOT_INSTALLED' ? '설치하기' : state === 'UPDATE_AVAILABLE' ? '갱신하기' : state === 'UNKNOWN' ? '자료실 것으로 설치' : null

  return (
    <>
      <div className="flex items-start gap-4">
        <div className="item-icon h-16 w-16 border-[3px] text-3xl" style={{ borderColor: look.ring, boxShadow: `0 0 14px ${look.glow}` }} aria-hidden="true">
          {entry.name.charAt(0).toUpperCase()}
        </div>
        <div className="min-w-0 flex-1">
          <h2 className="ink break-words text-3xl font-bold">{entry.name}</h2>
          <p className="ink-soft mt-1 text-sm">
            {[item?.version && `자료실 v${item.version}`, item && formatBytes(item.fileSize), item?.updatedAt && `${formatAgo(item.updatedAt)} 갱신`].filter(Boolean).join(' · ') || '자료실에 없는 폴더'}
          </p>
        </div>
        {desktop && m.label && (
          <span className="-rotate-3 rounded border px-3 py-1 text-xs font-bold" style={{ color: look.text, borderColor: look.text }}>{m.label}</span>
        )}
      </div>

      <div className="divider" aria-hidden="true"><i /></div>

      {item?.note && (
        <section className="space-y-1">
          <h3 className="ink-red text-lg font-bold">메모</h3>
          <p className="ink whitespace-pre-line leading-relaxed">{item.note}</p>
        </section>
      )}

      {desktop && (
        <section className="space-y-2">
          <h3 className="ink-red text-lg font-bold">내 폴더와 비교</h3>
          {!item ? (
            <p className="ink leading-relaxed">자료실에 없는 폴더입니다. 이 앱이 업데이트하지 않으니, 다른 애드온이 쓰는 폴더가 아닌지 확인한 뒤 지우세요.</p>
          ) : !local ? (
            <p className="ink">아직 내 폴더에 없습니다. 설치하면 자료실의 내용이 그대로 들어옵니다.</p>
          ) : (
            <div className="flex flex-wrap items-center gap-3 text-sm">
              <span className="ink rounded border border-[#b99a5a] bg-[rgba(75,45,15,.12)] px-3 py-1.5">내 폴더 {local.version ? `v${local.version}` : '버전 표시 없음'}</span>
              <span className="ink-soft" aria-hidden="true">→</span>
              <span className="rounded border px-3 py-1.5 font-bold" style={{ color: look.text, borderColor: look.ring }}>자료실 {item.version ? `v${item.version}` : '버전 표시 없음'}</span>
              <span className="ink-soft w-full text-xs">
                {ownState === 'UP_TO_DATE' ? '내용이 자료실과 같습니다.' : ownState === 'UPDATE_AVAILABLE' ? '내용이 자료실과 다릅니다. 갱신하면 자료실의 내용으로 바뀝니다.' : '자료실과 같은지 확인할 수 없습니다.'}
              </span>
            </div>
          )}
        </section>
      )}

      {item && (deps.length > 0 || item.requires.length > 0) && (
        <section className="space-y-2">
          <h3 className="ink-red text-lg font-bold">함께 설치되는 애드온</h3>
          <p className="ink-soft text-sm">이 애드온을 설치·갱신하면 아래 애드온도 함께 설치·갱신됩니다. 이미 최신이면 건드리지 않습니다.</p>
          <ul className="flex flex-wrap gap-2">
            {deps.map((dep) => {
              const depState = desktop ? libraryState(dep, localByName.get(dep.name.toLowerCase()), hashes) : null
              const dm = (depState && RING[depState]) || RING.OTHER
              const label = depState === 'UP_TO_DATE' ? '최신' : depState === 'UPDATE_AVAILABLE' ? '갱신 가능' : depState === 'NOT_INSTALLED' ? '설치 안 함' : ''
              return (
                <li key={dep.id} className="rounded border px-3 py-1 text-sm" style={{ color: dm.text, borderColor: dm.ring }}>
                  {dep.name}{dep.version && <span className="ml-1.5 text-xs opacity-70">v{dep.version}</span>}{label && <span className="ml-2 text-xs opacity-80">{label}</span>}
                </li>
              )
            })}
            {item.requires.filter((r) => !libraryByName.has(r.toLowerCase())).map((r) => (
              <li key={r} className="rounded border px-3 py-1 text-sm" style={{ color: '#9a4a00', borderColor: '#d9822b' }}>{r}<span className="ml-2 text-xs opacity-80">자료실에 없음</span></li>
            ))}
          </ul>
        </section>
      )}

      {missing.length > 0 && (
        <p className="text-sm font-medium text-[#9a4a00]">⚠ 함께 필요한 {missing.join(', ')} 이(가) 자료실에 없습니다. 관리자에게 알려 주세요.</p>
      )}
      {message && <p className="whitespace-pre-line text-sm font-medium text-[#9a4a00]">{message}</p>}

      {desktop && (
        <div className="mt-auto flex flex-wrap items-center gap-3 pt-2">
          {primary && item && (
            <button className="btn btn-primary !px-10 !py-3 font-[family-name:var(--font-display)] !text-xl font-bold" disabled={busy || !canAct} onClick={onInstall}>{primary}</button>
          )}
          {state !== 'NOT_INSTALLED' && (
            <button className="btn-parch !px-4 !py-3" disabled={busy || !canAct} onClick={onRemove}>폴더에서 지우기</button>
          )}
        </div>
      )}
    </>
  )
}
