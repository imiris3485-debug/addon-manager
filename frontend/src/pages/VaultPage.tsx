import { useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useEnv } from '../lib/EnvContext'
import { formatAgo, formatDate } from '../lib/format'
import { applySetup, libraryState, type LibraryState } from '../lib/library'
import { isDesktop } from '../lib/nativeFs'
import { scanFolders } from '../lib/scanner'
import type { LocalFolder, Snapshot } from '../lib/types'
import { useAutoSnapshot } from '../lib/useAutoSnapshot'
import { useFolder } from '../lib/useFolder'
import { useInstalledHashes } from '../lib/useInstalledHashes'

/** 아이템 품질 색: 최신=초록, 갱신=주황, 그 밖=회색 */
const TILE: Record<LibraryState, { ring: string; text: string; label: string }> = {
  UP_TO_DATE: { ring: '#2e9a2e', text: '#17661a', label: '설치돼 있음' },
  UPDATE_AVAILABLE: { ring: '#d9822b', text: '#9a4a00', label: '갱신 가능' },
  NOT_INSTALLED: { ring: '#8b7b63', text: '#5f5140', label: '없음' },
  UNKNOWN: { ring: '#8b7b63', text: '#5f5140', label: '직접 설치' },
}

const titleOf = (s: Snapshot) => s.label ?? '자동으로 남긴 기록'

/**
 * 보관함: 내 애드온 목록의 기록. 앱이 폴더를 읽을 때마다 목록이 바뀌었으면 자동으로 남기고, 이름을 붙여 직접 보관할 수도 있다.
 * 폴더를 다 지웠거나 와우를 새로 깔았어도 기록을 골라 그대로 다시 설치하거나, 다른 사람에게 편지로 보낼 수 있다.
 */
export default function VaultPage() {
  const { current } = useEnv()
  const desktop = isDesktop()
  const envId = desktop ? current?.id ?? null : null
  const qc = useQueryClient()
  const folder = useFolder(envId)
  const navigate = useNavigate()

  const { data: snapshots, isLoading, error } = useQuery({ queryKey: ['snapshots'], queryFn: api.snapshots })
  const { data: library } = useQuery({ queryKey: ['library'], queryFn: api.library })
  const { data: local, refetch: refetchLocal, dataUpdatedAt } = useQuery<LocalFolder[]>({
    queryKey: ['local-folders', envId],
    queryFn: () => scanFolders(folder.handle!),
    enabled: desktop && envId != null && !!folder.handle,
    staleTime: 0,
  })
  const { hashes } = useInstalledHashes(envId, folder.handle, local, library, dataUpdatedAt)
  useAutoSnapshot(envId, local, library)

  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [label, setLabel] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState<string | null>(null)

  const libraryByName = useMemo(() => new Map((library ?? []).map((a) => [a.name.toLowerCase(), a])), [library])
  const localByName = useMemo(() => new Map((local ?? []).map((l) => [l.name.toLowerCase(), l])), [local])
  const stateOf = (name: string): LibraryState => {
    const item = libraryByName.get(name.toLowerCase())
    return item ? libraryState(item, localByName.get(name.toLowerCase()), hashes) : 'NOT_INSTALLED'
  }
  const toInstall = (s: Snapshot) => s.addons.filter((n) => ['NOT_INSTALLED', 'UPDATE_AVAILABLE'].includes(stateOf(n)))

  const list = snapshots ?? []
  const selected = list.find((s) => s.id === selectedId) ?? list[0]
  const myNames = (local ?? []).map((l) => libraryByName.get(l.name.toLowerCase())?.name).filter((n): n is string => !!n)

  const keep = async () => {
    if (envId == null || !label.trim() || myNames.length === 0) return
    setNotice(null)
    setBusy('keep')
    try {
      const saved = await api.createSnapshot(envId, label.trim(), myNames)
      setLabel('')
      setSelectedId(saved.id)
      await qc.invalidateQueries({ queryKey: ['snapshots'] })
    } catch (e) {
      setNotice((e as Error).message)
    } finally {
      setBusy(null)
    }
  }

  const restore = async (s: Snapshot) => {
    if (envId == null || !library || !local) return
    const say = (text: string) => setMessage((m) => ({ ...m, [s.id]: text }))
    setBusy(s.id)
    say('기록한 목록대로 설치하는 중...')
    try {
      const root = await folder.requireAccess()
      const r = await applySetup(envId, root, s.addons, library, local, hashes, say)
      const lines = [`설치·갱신 ${r.installed.length}개${r.installed.length ? `: ${r.installed.join(', ')}` : ''}`]
      if (r.skipped.length > 0) lines.push(`건드리지 않음(이미 최신이거나 직접 설치한 폴더) ${r.skipped.length}개`)
      if (r.missing.length > 0) lines.push(`⚠ 자료실에 없어 설치하지 못함: ${r.missing.join(', ')}`)
      for (const f of r.failed) lines.push(`⚠ ${f.name} 실패: ${f.reason}`)
      say(lines.join('\n'))
      await refetchLocal()
    } catch (e) {
      say(`실패: ${(e as Error).message}`)
    } finally {
      setBusy(null)
    }
  }

  const remove = async (s: Snapshot) => {
    if (!confirm(`'${titleOf(s)}' 기록을 지울까요?\n(이미 설치한 애드온은 그대로입니다)`)) return
    setNotice(null)
    try {
      await api.deleteSnapshot(s.id)
      await qc.invalidateQueries({ queryKey: ['snapshots'] })
    } catch (e) {
      setNotice((e as Error).message)
    }
  }

  return (
    <div className="space-y-3">
      {!desktop && (
        <p className="rounded-md border border-indigo-500/30 bg-indigo-500/10 p-3 text-sm text-indigo-200">
          웹에서는 기록을 보고 편지로 보낼 수 있습니다. 기록대로 설치하려면 <Link className="underline" to="/download">데스크톱 앱</Link>에서 하세요.
        </p>
      )}
      {notice && <p className="text-sm text-rose-400">{notice}</p>}
      {error && <p className="text-sm text-rose-400">{(error as Error).message}</p>}

      <section className="book">
        {/* 왼쪽 쪽: 기록 목록 */}
        <div className="page-left flex w-full flex-col gap-3 p-5 md:w-[392px] md:shrink-0">
          <h1 className="ink text-2xl font-bold">보관함</h1>
          <p className="ink-soft text-xs leading-relaxed">
            앱이 폴더를 읽을 때마다 내 애드온 목록이 바뀌었으면 자동으로 기록합니다. 폴더를 다 지우거나 와우를 새로 깔아도 여기서 골라 그대로 다시 설치할 수 있습니다.
          </p>

          {desktop && (
            <div className="space-y-2 rounded border border-[#b99a5a] p-3">
              <p className="ink text-sm font-medium">지금 목록 보관하기 <span className="ink-soft text-xs font-normal">(자료실 애드온 {myNames.length}개)</span></p>
              <div className="flex gap-2">
                <input className="parch-input" placeholder="이름 (예: 이사 가기 전 세팅)" maxLength={50} value={label} onChange={(e) => setLabel(e.target.value)} />
                <button className="btn btn-primary shrink-0" disabled={busy !== null || !label.trim() || myNames.length === 0} onClick={keep}>보관</button>
              </div>
            </div>
          )}

          {isLoading && <p className="ink-soft text-sm">불러오는 중...</p>}
          {snapshots && list.length === 0 && (
            <p className="ink-soft text-sm leading-relaxed">아직 기록이 없습니다. 자료실에서 애드온을 설치하면 이곳에 목록이 남습니다.</p>
          )}
          <ul className="flex max-h-[44vh] flex-col gap-1 overflow-y-auto">
            {list.map((s) => (
              <li key={s.id}>
                <button className="quest-row" aria-current={s.id === selected?.id} onClick={() => setSelectedId(s.id)}>
                  <span className={`quest-mark ${s.source === 'MANUAL' ? 'gold' : 'grey'}`} aria-hidden="true">{s.source === 'MANUAL' ? '★' : '◆'}</span>
                  <span className="min-w-0 flex-1">
                    <span className="ink block truncate font-[family-name:var(--font-display)] text-lg font-bold">{titleOf(s)}</span>
                    <span className="ink-soft block truncate text-xs">
                      {s.addons.length}개{s.gameVersion && ` · 게임 ${s.gameVersion}`}{s.createdAt && ` · ${formatAgo(s.createdAt)}`}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
          <p className="ink-soft mt-auto text-xs"><b className="text-[#b07a00]">★</b> 직접 보관 &nbsp; <b>◆</b> 자동 기록(최근 10개)</p>
        </div>

        {/* 오른쪽 쪽: 고른 기록 */}
        <article className="page-right flex min-w-0 flex-1 flex-col gap-4 p-6 md:p-8">
          {!selected ? (
            <p className="ink-soft">왼쪽에서 기록을 골라 보세요.</p>
          ) : (
            <>
              <div>
                <h2 className="ink break-words text-3xl font-bold">{titleOf(selected)}</h2>
                <p className="ink-soft mt-1 text-sm">
                  {[selected.createdAt && formatDate(selected.createdAt), selected.gameVersion && `게임 ${selected.gameVersion}`, `${selected.addons.length}개`].filter(Boolean).join(' · ')}
                </p>
              </div>
              <div className="divider" aria-hidden="true"><i /></div>

              <section className="space-y-2">
                <h3 className="ink-red text-lg font-bold">그때의 애드온</h3>
                <ul className="grid grid-cols-[repeat(auto-fill,minmax(96px,1fr))] gap-3">
                  {selected.addons.map((n) => {
                    const t = TILE[desktop ? stateOf(n) : 'NOT_INSTALLED']
                    return (
                      <li key={n} className="flex flex-col items-center gap-1 text-center">
                        <div className="item-icon h-12 w-12 border-[3px] text-xl" style={{ borderColor: t.ring, boxShadow: `0 0 8px ${t.ring}66` }} aria-hidden="true">{n.charAt(0).toUpperCase()}</div>
                        <span className="ink w-full break-words text-xs font-medium leading-tight">{n}</span>
                        {desktop && <span className="text-[11px]" style={{ color: t.text }}>{t.label}</span>}
                      </li>
                    )
                  })}
                </ul>
                {selected.missingAddons.length > 0 && (
                  <p className="text-xs font-medium text-[#9a4a00]">⚠ 자료실에서 사라져 설치할 수 없는 애드온: {selected.missingAddons.join(', ')}</p>
                )}
              </section>

              {message[selected.id] && <p className="whitespace-pre-line text-sm font-medium text-[#9a4a00]">{message[selected.id]}</p>}

              <div className="mt-auto flex flex-wrap items-center gap-3 pt-2">
                {desktop && (
                  <button className="btn btn-primary !px-8 !py-3 font-[family-name:var(--font-display)] !text-lg font-bold"
                          disabled={busy !== null || !folder.handle || !local || toInstall(selected).length === 0} onClick={() => restore(selected)}>
                    {toInstall(selected).length === 0 ? '이 목록의 애드온이 모두 설치돼 있습니다' : `이 목록대로 설치하기 (${toInstall(selected).length}개)`}
                  </button>
                )}
                <button className="btn-parch !px-4 !py-3" onClick={() => navigate('/mailbox', { state: { compose: { name: titleOf(selected), addons: selected.addons } } })}>편지로 보내기</button>
                <button className="btn-parch !px-4 !py-3" disabled={busy !== null} onClick={() => remove(selected)}>기록 지우기</button>
              </div>
            </>
          )}
        </article>
      </section>
    </div>
  )
}
