import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useEnv } from '../lib/EnvContext'
import { formatAgo } from '../lib/format'
import { applySetup, libraryState, type LibraryState } from '../lib/library'
import { isDesktop } from '../lib/nativeFs'
import { scanFolders } from '../lib/scanner'
import type { LibraryAddon, LocalFolder, Person, Setup, SetupInput, Snapshot } from '../lib/types'
import { useFolder } from '../lib/useFolder'
import { useInstalledHashes } from '../lib/useInstalledHashes'

type Box = 'RECEIVED' | 'SENT'
interface Draft { id: string | null; input: SetupInput }

const EMPTY: SetupInput = { name: '', description: '', addons: [], targets: [] }

/** 아이템 품질 색: 최신=초록, 갱신=주황, 그 밖=회색 */
const TILE: Record<LibraryState, { ring: string; text: string; label: string }> = {
  UP_TO_DATE: { ring: '#2e9a2e', text: '#17661a', label: '최신' },
  UPDATE_AVAILABLE: { ring: '#d9822b', text: '#9a4a00', label: '갱신 가능' },
  NOT_INSTALLED: { ring: '#8b7b63', text: '#5f5140', label: '설치 안 함' },
  UNKNOWN: { ring: '#8b7b63', text: '#5f5140', label: '직접 설치' },
}

/** 우편함: 애드온 목록을 편지로 보내고 받는다. 받은 편지는 동봉된 애드온을 한 번에 설치할 수 있다. */
export default function MailboxPage() {
  const { current } = useEnv()
  const desktop = isDesktop()
  const envId = desktop ? current?.id ?? null : null
  const qc = useQueryClient()
  const folder = useFolder(envId)
  const navigate = useNavigate()
  const location = useLocation()
  // 보관함에서 "편지로 보내기"를 눌러 넘어온 경우: 그 목록을 담아 편지 쓰기를 연다
  const prefill = (location.state as { compose?: { name?: string; addons: string[] } } | null)?.compose

  const { data: setups, isLoading, error } = useQuery({ queryKey: ['setups'], queryFn: api.setups })
  const { data: library } = useQuery({ queryKey: ['library'], queryFn: api.library })
  const { data: people } = useQuery({ queryKey: ['setup-people'], queryFn: api.setupPeople })
  const { data: snapshots } = useQuery({ queryKey: ['snapshots'], queryFn: api.snapshots })
  // 이 PC 의 AddOns 폴더를 읽어 동봉된 애드온의 설치 상태를 보여 준다(앱에서만)
  const { data: local, refetch: refetchLocal, dataUpdatedAt } = useQuery<LocalFolder[]>({
    queryKey: ['local-folders', envId],
    queryFn: () => scanFolders(folder.handle!),
    enabled: desktop && envId != null && !!folder.handle,
    staleTime: 0,
  })
  const { hashes } = useInstalledHashes(envId, folder.handle, local, library, dataUpdatedAt)

  const [box, setBox] = useState<Box>(prefill ? 'SENT' : 'RECEIVED')
  const [draft, setDraft] = useState<Draft | null>(prefill ? { id: null, input: { ...EMPTY, name: prefill.name ?? '', addons: prefill.addons } } : null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState<string | null>(null)

  // 넘어온 정보는 한 번만 쓰고 지운다(새로고침해도 편지 쓰기가 다시 열리지 않게)
  useEffect(() => {
    if (prefill) navigate(location.pathname, { replace: true, state: null })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const libraryByName = useMemo(() => new Map((library ?? []).map((a) => [a.name.toLowerCase(), a])), [library])
  const localByName = useMemo(() => new Map((local ?? []).map((l) => [l.name.toLowerCase(), l])), [local])
  const stateOf = (name: string): LibraryState => {
    const item = libraryByName.get(name.toLowerCase())
    return item ? libraryState(item, localByName.get(name.toLowerCase()), hashes) : 'NOT_INSTALLED'
  }
  const toInstall = (s: Setup) => s.addons.filter((n) => ['NOT_INSTALLED', 'UPDATE_AVAILABLE'].includes(stateOf(n)))

  const received = setups?.received ?? []
  const sent = setups?.mine ?? []
  const list = box === 'RECEIVED' ? received : sent
  const selected = draft ? undefined : list.find((s) => s.id === selectedId) ?? list[0]
  const unread = received.filter((s) => !s.read).length

  // 받은 편지를 열면 읽음으로 표시한다(실패해도 보는 데는 지장 없다)
  useEffect(() => {
    if (box !== 'RECEIVED' || draft || !selected || selected.read) return
    api.markLetterRead(selected.id).then(() => qc.invalidateQueries({ queryKey: ['setups'] })).catch(() => undefined)
  }, [box, draft, selected, qc])

  const apply = async (s: Setup) => {
    if (envId == null || !library || !local) return
    const say = (text: string) => setMessage((m) => ({ ...m, [s.id]: text }))
    setBusy(s.id)
    say('편지 속 애드온을 설치하는 중...')
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

  const remove = async (s: Setup) => {
    if (!confirm(`'${s.name}' 편지를 지울까요?\n받은 사람의 우편함에서도 사라집니다. (이미 설치한 애드온은 그대로입니다)`)) return
    setNotice(null)
    try {
      await api.deleteSetup(s.id)
      await qc.invalidateQueries({ queryKey: ['setups'] })
    } catch (e) {
      setNotice((e as Error).message)
    }
  }

  const send = async () => {
    if (!draft) return
    setNotice(null)
    setBusy('form')
    try {
      const saved = draft.id ? await api.updateSetup(draft.id, draft.input) : await api.createSetup(draft.input)
      setDraft(null)
      setBox('SENT')
      setSelectedId(saved.id)
      await qc.invalidateQueries({ queryKey: ['setups'] })
    } catch (e) {
      setNotice((e as Error).message)
    } finally {
      setBusy(null)
    }
  }

  const startDraft = (s: Setup | null) =>
    setDraft(s
      ? { id: s.id, input: { name: s.name, description: s.description ?? '', addons: [...s.addons], targets: s.targets.map((t) => t.loginId) } }
      : { id: null, input: { ...EMPTY } })

  const tabCls = (active: boolean) =>
    `rounded-t px-4 py-2 text-sm transition ${active ? 'bg-[#e9d8b0] font-bold text-[#3b2412] shadow-[inset_0_2px_0_#fff3cf]' : 'text-slate-300 hover:text-white'}`

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end gap-2">
        <div className="flex gap-1">
          <button className={tabCls(box === 'RECEIVED')} onClick={() => { setBox('RECEIVED'); setDraft(null) }}>
            받은 편지 {received.length}{unread > 0 && <span className="ml-1.5 rounded-full bg-[#8a1c1c] px-1.5 py-0.5 text-xs font-bold text-[#ffe9bf]">새 편지 {unread}</span>}
          </button>
          <button className={tabCls(box === 'SENT')} onClick={() => { setBox('SENT'); setDraft(null) }}>보낸 편지 {sent.length}</button>
        </div>
        <div className="ml-auto pb-1">
          <button className="btn btn-primary" disabled={draft !== null} onClick={() => { setBox('SENT'); startDraft(null) }}>편지 쓰기</button>
        </div>
      </div>

      {!desktop && (
        <p className="rounded-md border border-indigo-500/30 bg-indigo-500/10 p-3 text-sm text-indigo-200">
          웹에서는 편지를 쓰고 읽을 수 있습니다. 동봉된 애드온의 설치는 <Link className="underline" to="/download">데스크톱 앱</Link>에서 하세요.
        </p>
      )}
      {notice && <p className="text-sm text-rose-400">{notice}</p>}
      {error && <p className="text-sm text-rose-400">{(error as Error).message}</p>}

      <section className="book">
        {/* 왼쪽 쪽: 편지 목록 */}
        <div className="page-left flex w-full flex-col gap-3 p-5 md:w-[392px] md:shrink-0">
          <h1 className="ink text-2xl font-bold">{box === 'RECEIVED' ? '받은 편지' : '보낸 편지'}</h1>
          {isLoading && <p className="ink-soft text-sm">불러오는 중...</p>}
          {setups && list.length === 0 && (
            <p className="ink-soft text-sm leading-relaxed">
              {box === 'RECEIVED' ? '아직 받은 편지가 없습니다. 다른 모험가가 애드온 목록을 보내 주면 여기에 도착합니다.' : '보낸 편지가 없습니다. 위의 "편지 쓰기"로 내 애드온 목록을 보내 보세요.'}
            </p>
          )}
          <ul className="flex max-h-[54vh] flex-col gap-1 overflow-y-auto">
            {list.map((s) => {
              const isUnread = box === 'RECEIVED' && !s.read
              return (
                <li key={s.id}>
                  <button className="quest-row" aria-current={!draft && s.id === selected?.id} onClick={() => { setDraft(null); setSelectedId(s.id) }}>
                    <Envelope unread={isUnread} />
                    <span className="min-w-0 flex-1">
                      <span className={`ink block truncate font-[family-name:var(--font-display)] text-lg ${isUnread ? 'font-bold' : ''}`}>{s.name}</span>
                      <span className="ink-soft block truncate text-xs">
                        {box === 'RECEIVED' ? `${s.ownerName} 님` : s.targets.length > 0 ? `받는 사람 ${s.targets.map((t) => t.name).join(', ')}` : '나만 보관'}
                        {s.sentAt && ` · ${formatAgo(s.sentAt)}`}
                      </span>
                    </span>
                    {isUnread && <span className="h-2.5 w-2.5 shrink-0 rounded-full bg-[#a52a22]" aria-label="안 읽은 편지" />}
                  </button>
                </li>
              )
            })}
          </ul>
        </div>

        {/* 오른쪽 쪽: 편지 읽기 / 편지 쓰기 */}
        <article className="page-right flex min-w-0 flex-1 flex-col gap-4 p-6 md:p-8">
          {draft && library ? (
            <Compose
              draft={draft}
              library={library}
              people={people ?? []}
              snapshots={snapshots ?? []}
              installedNames={desktop ? (local ?? []).map((l) => l.name) : []}
              busy={busy === 'form'}
              onChange={(input) => setDraft({ ...draft, input })}
              onSend={send}
              onCancel={() => setDraft(null)}
            />
          ) : !selected ? (
            <p className="ink-soft">왼쪽에서 편지를 골라 열어 보세요.</p>
          ) : (
            <LetterView
              setup={selected}
              box={box}
              desktop={desktop}
              pending={desktop ? toInstall(selected).length : 0}
              stateOf={stateOf}
              message={message[selected.id]}
              busy={busy !== null}
              canInstall={!!folder.handle && !!local}
              onApply={() => apply(selected)}
              onEdit={() => startDraft(selected)}
              onDelete={() => remove(selected)}
            />
          )}
        </article>
      </section>
    </div>
  )
}

/** 봉투 그림: 안 읽은 편지는 붉은 밀랍으로 봉해져 있고, 읽은 편지는 열려 있다. */
function Envelope({ unread }: { unread: boolean }) {
  return unread ? (
    <svg width="34" height="26" viewBox="0 0 34 26" aria-hidden="true" className="shrink-0">
      <rect x="1" y="1" width="32" height="24" rx="3" fill="#f3e6c2" stroke="#8a6a2c" strokeWidth="1.5" />
      <path d="M2 3 L17 15 L32 3" fill="none" stroke="#8a6a2c" strokeWidth="1.5" />
      <circle cx="17" cy="15" r="5" fill="#a52a22" stroke="#6a1010" />
    </svg>
  ) : (
    <svg width="34" height="26" viewBox="0 0 34 26" aria-hidden="true" className="shrink-0 opacity-60">
      <rect x="1" y="9" width="32" height="16" rx="3" fill="#e2cf9f" stroke="#8a6a2c" strokeWidth="1.5" />
      <path d="M2 10 L17 1 L32 10" fill="#efe0b8" stroke="#8a6a2c" strokeWidth="1.5" />
    </svg>
  )
}

function LetterView({ setup, box, desktop, pending, stateOf, message, busy, canInstall, onApply, onEdit, onDelete }: {
  setup: Setup
  box: Box
  desktop: boolean
  pending: number
  stateOf: (name: string) => LibraryState
  message: string | undefined
  busy: boolean
  canInstall: boolean
  onApply: () => void
  onEdit: () => void
  onDelete: () => void
}) {
  const sender = box === 'RECEIVED' ? setup.ownerName : '나'
  return (
    <>
      <div className="letter flex flex-col gap-4 p-7">
        <div className="flex items-start gap-3">
          <h2 className="ink min-w-0 flex-1 break-words text-2xl font-bold">{setup.name}</h2>
          {setup.sentAt && <span className="ink-soft shrink-0 text-xs">{formatAgo(setup.sentAt)}</span>}
        </div>
        {box === 'SENT' && (
          <p className="ink-soft text-sm">{setup.targets.length > 0 ? `받는 사람: ${setup.targets.map((t) => `${t.name}(${t.loginId})`).join(', ')}` : '받는 사람이 없어 나만 볼 수 있습니다.'}</p>
        )}
        <p className="letter-body min-h-16">{setup.description || (box === 'RECEIVED' ? '(편지글 없이 애드온만 동봉된 편지입니다)' : '(편지글이 없습니다)')}</p>

        <div>
          <p className="ink-soft mb-2 text-sm">동봉한 애드온 {setup.addons.length}개</p>
          <ul className="grid grid-cols-[repeat(auto-fill,minmax(92px,1fr))] gap-3">
            {setup.addons.map((n) => {
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
          {setup.missingAddons.length > 0 && (
            <p className="mt-2 text-xs font-medium text-[#9a4a00]">⚠ 자료실에서 사라져 설치할 수 없는 애드온: {setup.missingAddons.join(', ')}</p>
          )}
        </div>

        <div className="flex items-end justify-end gap-3">
          <div className="text-right">
            <div className="ink-soft text-xs">보낸 사람</div>
            <div className="ink font-[family-name:var(--font-display)] text-lg font-bold">{sender}</div>
          </div>
          <div className="wax-seal" aria-hidden="true">{(sender || '?').charAt(0).toUpperCase()}</div>
        </div>
      </div>

      {message && <p className="whitespace-pre-line text-sm font-medium text-[#9a4a00]">{message}</p>}

      <div className="mt-auto flex flex-wrap items-center gap-3">
        {desktop && (
          <button className="btn btn-primary !px-8 !py-3 font-[family-name:var(--font-display)] !text-lg font-bold" disabled={busy || !canInstall || pending === 0} onClick={onApply}>
            {pending === 0 ? '동봉한 애드온이 모두 설치돼 있습니다' : `동봉한 애드온 설치하기 (${pending}개)`}
          </button>
        )}
        {box === 'SENT' && (
          <>
            <button className="btn-parch !px-4 !py-3" disabled={busy} onClick={onEdit}>고쳐서 다시 보내기</button>
            <button className="btn-parch !px-4 !py-3" disabled={busy} onClick={onDelete}>편지 지우기</button>
          </>
        )}
      </div>
    </>
  )
}

function Compose({ draft, library, people, snapshots, installedNames, busy, onChange, onSend, onCancel }: {
  draft: Draft
  library: LibraryAddon[]
  people: Person[]
  snapshots: Snapshot[]
  installedNames: string[]
  busy: boolean
  onChange: (input: SetupInput) => void
  onSend: () => void
  onCancel: () => void
}) {
  const { input } = draft
  const [query, setQuery] = useState('')
  const picked = useMemo(() => new Set(input.addons.map((a) => a.toLowerCase())), [input.addons])
  const q = query.trim().toLowerCase()
  const shown = library.filter((a) => !q || a.name.toLowerCase().includes(q))

  const toggleAddon = (name: string) =>
    onChange({ ...input, addons: picked.has(name.toLowerCase()) ? input.addons.filter((a) => a.toLowerCase() !== name.toLowerCase()) : [...input.addons, name] })
  const toggleTarget = (loginId: string) =>
    onChange({ ...input, targets: input.targets.includes(loginId) ? input.targets.filter((t) => t !== loginId) : [...input.targets, loginId] })
  const addNames = (names: string[]) => {
    const libNames = new Map(library.map((a) => [a.name.toLowerCase(), a.name]))
    const add = names.map((n) => libNames.get(n.toLowerCase())).filter((n): n is string => !!n)
    onChange({ ...input, addons: Array.from(new Set([...input.addons, ...add])) })
  }

  return (
    <div className="flex flex-col gap-4">
      <h2 className="ink text-2xl font-bold">{draft.id ? '편지 고쳐 쓰기' : '새 편지 쓰기'}</h2>

      <section className="space-y-2">
        <h3 className="ink-red text-base font-bold">받는 사람 <span className="ink-soft text-xs font-normal">({input.targets.length}명 · 고르지 않으면 나만 봅니다)</span></h3>
        <ul className="flex flex-wrap gap-x-4 gap-y-1">
          {people.map((p) => (
            <li key={p.loginId}>
              <label className="ink flex cursor-pointer items-center gap-2 text-sm">
                <input type="checkbox" checked={input.targets.includes(p.loginId)} onChange={() => toggleTarget(p.loginId)} />
                {p.name} <span className="ink-soft text-xs">({p.loginId})</span>
              </label>
            </li>
          ))}
          {people.length === 0 && <li className="ink-soft text-sm">편지를 보낼 수 있는 다른 사용자가 없습니다.</li>}
        </ul>
      </section>

      <input className="parch-input" placeholder="제목 (예: 클래식 레이드용 기본 세트)" maxLength={50} value={input.name} onChange={(e) => onChange({ ...input, name: e.target.value })} />
      <textarea className="parch-input min-h-28 leading-7" placeholder="편지글 (누구에게 필요한 목록인지, 쓰는 방법이나 안부를 적어 보세요)" maxLength={1000} value={input.description} onChange={(e) => onChange({ ...input, description: e.target.value })} />

      <section className="space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="ink-red text-base font-bold">동봉할 애드온 <span className="ink-soft text-xs font-normal">({input.addons.length}개)</span></h3>
          {installedNames.length > 0 && <button type="button" className="btn-parch !px-2 !py-0.5 text-xs" onClick={() => addNames(installedNames)}>내 PC 에 설치된 것 담기</button>}
          {snapshots.length > 0 && (
            <select className="parch-input !w-auto !py-0.5 text-xs" aria-label="보관함 기록에서 담기" value="" onChange={(e) => { const s = snapshots.find((x) => x.id === e.target.value); if (s) addNames(s.addons) }}>
              <option value="">보관함 기록에서 담기...</option>
              {snapshots.map((s) => <option key={s.id} value={s.id}>{(s.label ?? '자동 기록') + ` · ${s.addons.length}개 · ${s.createdAt ? formatAgo(s.createdAt) : ''}`}</option>)}
            </select>
          )}
          {input.addons.length > 0 && <button type="button" className="btn-parch !px-2 !py-0.5 text-xs" onClick={() => onChange({ ...input, addons: [] })}>모두 해제</button>}
          <input className="parch-input ml-auto !w-44 !py-0.5 text-xs" placeholder="이름 검색" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <ul className="grid max-h-56 grid-cols-1 gap-1 overflow-y-auto rounded border border-[#b99a5a] p-2 sm:grid-cols-2 lg:grid-cols-3">
          {shown.map((a) => (
            <li key={a.id}>
              <label className="ink flex cursor-pointer items-center gap-2 text-sm">
                <input type="checkbox" checked={picked.has(a.name.toLowerCase())} onChange={() => toggleAddon(a.name)} />
                <span className="truncate">{a.name}</span>
              </label>
            </li>
          ))}
          {shown.length === 0 && <li className="ink-soft text-sm">검색 결과가 없습니다.</li>}
        </ul>
      </section>

      <div className="flex gap-3">
        <button className="btn btn-primary !px-8 !py-3 font-[family-name:var(--font-display)] !text-lg font-bold" disabled={busy || !input.name.trim() || input.addons.length === 0} onClick={onSend}>
          {busy ? '봉하는 중...' : input.targets.length > 0 ? '편지 보내기' : '나에게 보관하기'}
        </button>
        <button className="btn-parch !px-4 !py-3" disabled={busy} onClick={onCancel}>취소</button>
      </div>
    </div>
  )
}
