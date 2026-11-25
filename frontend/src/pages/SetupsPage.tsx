import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useEnv } from '../lib/EnvContext'
import { formatAgo } from '../lib/format'
import { applySetup, libraryState, type LibraryState } from '../lib/library'
import { useInstalledHashes } from '../lib/useInstalledHashes'
import { isDesktop } from '../lib/nativeFs'
import { scanFolders } from '../lib/scanner'
import type { LibraryAddon, LocalFolder, Setup, SetupInput } from '../lib/types'
import { useFolder } from '../lib/useFolder'

const STATE_LABEL: Record<LibraryState, { label: string; cls: string }> = {
  NOT_INSTALLED: { label: '설치 안 함', cls: 'bg-slate-500/20 text-slate-300' },
  UP_TO_DATE: { label: '최신', cls: 'bg-emerald-500/15 text-emerald-300' },
  UPDATE_AVAILABLE: { label: '업데이트 가능', cls: 'bg-amber-500/15 text-amber-300' },
  UNKNOWN: { label: '직접 설치(확인 불가)', cls: 'bg-slate-500/20 text-slate-300' },
}

const EMPTY: SetupInput = { name: '', description: '', addons: [], targets: [] }

export default function SetupsPage() {
  const { current } = useEnv()
  const desktop = isDesktop()
  const envId = desktop ? current?.id ?? null : null
  const qc = useQueryClient()
  const folder = useFolder(envId)

  const { data: setups, isLoading, error } = useQuery({ queryKey: ['setups'], queryFn: api.setups })
  const { data: library } = useQuery({ queryKey: ['library'], queryFn: api.library })
  const { data: people } = useQuery({ queryKey: ['setup-people'], queryFn: api.setupPeople })
  // 이 PC 의 AddOns 폴더를 읽어 세팅의 애드온별 설치 상태를 보여 준다(앱에서만)
  const { data: local, refetch: refetchLocal, dataUpdatedAt } = useQuery<LocalFolder[]>({
    queryKey: ['local-folders', envId],
    queryFn: () => scanFolders(folder.handle!),
    enabled: desktop && envId != null && !!folder.handle,
    staleTime: 0,
  })

  const [editing, setEditing] = useState<{ id: string | null; input: SetupInput } | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState<string | null>(null)

  const libraryByName = useMemo(() => new Map((library ?? []).map((a) => [a.name.toLowerCase(), a])), [library])
  const localByName = useMemo(() => new Map((local ?? []).map((l) => [l.name.toLowerCase(), l])), [local])
  const { hashes } = useInstalledHashes(envId, folder.handle, local, library, dataUpdatedAt)

  const stateOf = (name: string): LibraryState => {
    const item = libraryByName.get(name.toLowerCase())
    return item ? libraryState(item, localByName.get(name.toLowerCase()), hashes) : 'NOT_INSTALLED'
  }

  const toInstall = (s: Setup) => s.addons.filter((n) => ['NOT_INSTALLED', 'UPDATE_AVAILABLE'].includes(stateOf(n)))

  const apply = async (s: Setup) => {
    if (envId == null || !library || !local) return
    const say = (text: string) => setMessage((m) => ({ ...m, [s.id]: text }))
    setBusy(s.id)
    say('설치를 시작합니다...')
    try {
      const root = await folder.requireAccess()
      const r = await applySetup(envId, root, s.addons, library, local, hashes, say)
      const lines = [`설치·업데이트 ${r.installed.length}개${r.installed.length ? `: ${r.installed.join(', ')}` : ''}`]
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
    if (!confirm(`'${s.name}' 세팅을 삭제할까요?\n공유받은 사람들에게서도 사라집니다. (이미 설치한 애드온은 그대로입니다)`)) return
    setNotice(null)
    try {
      await api.deleteSetup(s.id)
      await qc.invalidateQueries({ queryKey: ['setups'] })
    } catch (e) {
      setNotice((e as Error).message)
    }
  }

  const save = async () => {
    if (!editing) return
    setNotice(null)
    setBusy('form')
    try {
      if (editing.id) await api.updateSetup(editing.id, editing.input)
      else await api.createSetup(editing.input)
      setEditing(null)
      await qc.invalidateQueries({ queryKey: ['setups'] })
    } catch (e) {
      setNotice((e as Error).message)
    } finally {
      setBusy(null)
    }
  }

  const startEdit = (s: Setup | null) =>
    setEditing(s
      ? { id: s.id, input: { name: s.name, description: s.description ?? '', addons: [...s.addons], targets: s.targets.map((t) => t.loginId) } }
      : { id: null, input: { ...EMPTY } })

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="text-xl font-bold">세팅 공유</h1>
        <span className="text-sm text-slate-400">애드온 묶음을 만들어 다른 사람에게 보내고, 받은 세팅은 한 번에 설치합니다.</span>
        <div className="ml-auto">
          <button className="btn btn-primary" disabled={editing !== null} onClick={() => startEdit(null)}>새 세팅 만들기</button>
        </div>
      </div>

      {!desktop && (
        <p className="rounded-md border border-indigo-500/30 bg-indigo-500/10 p-3 text-sm text-indigo-200">
          웹에서는 세팅을 만들고 볼 수 있습니다. 설치는 <Link className="underline" to="/download">데스크톱 앱</Link>에서 하세요.
        </p>
      )}
      {notice && <p className="text-sm text-rose-400">{notice}</p>}
      {isLoading && <p className="text-slate-400">불러오는 중...</p>}
      {error && <p className="text-sm text-rose-400">{(error as Error).message}</p>}

      {editing && library && (
        <SetupForm
          library={library}
          people={people ?? []}
          editing={editing}
          installedNames={desktop ? (local ?? []).map((l) => l.name) : []}
          busy={busy === 'form'}
          onChange={(input) => setEditing({ ...editing, input })}
          onSave={save}
          onCancel={() => setEditing(null)}
        />
      )}

      <section className="space-y-2">
        <h2 className="font-semibold">받은 세팅 {setups && <span className="text-sm font-normal text-slate-400">{setups.received.length}개</span>}</h2>
        {setups && setups.received.length === 0 && <div className="card text-sm text-slate-400">아직 받은 세팅이 없습니다.</div>}
        <ul className="space-y-2">
          {setups?.received.map((s) => {
            const pending = desktop ? toInstall(s) : []
            return (
              <li key={s.id} className="card space-y-2">
                <div className="flex flex-wrap items-center gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="font-medium">{s.name}</div>
                    <div className="text-xs text-slate-500">
                      {s.ownerName}({s.ownerLoginId}) 님이 보냄{s.updatedAt && <> · {formatAgo(s.updatedAt)}</>}
                    </div>
                  </div>
                  {desktop && (
                    <button className="btn btn-primary" disabled={busy !== null || !folder.handle || !local || pending.length === 0} onClick={() => apply(s)}>
                      {pending.length === 0 ? '모두 설치됨' : `이 세팅대로 설치 (${pending.length}개)`}
                    </button>
                  )}
                </div>
                {s.description && <p className="whitespace-pre-line text-sm text-slate-300">{s.description}</p>}
                <AddonChips setup={s} desktop={desktop} stateOf={stateOf} />
                {message[s.id] && <p className="whitespace-pre-line text-xs text-amber-300">{message[s.id]}</p>}
              </li>
            )
          })}
        </ul>
      </section>

      <section className="space-y-2">
        <h2 className="font-semibold">내가 만든 세팅 {setups && <span className="text-sm font-normal text-slate-400">{setups.mine.length}개</span>}</h2>
        {setups && setups.mine.length === 0 && <div className="card text-sm text-slate-400">만든 세팅이 없습니다. 위의 '새 세팅 만들기'로 시작하세요.</div>}
        <ul className="space-y-2">
          {setups?.mine.map((s) => {
            const pending = desktop ? toInstall(s) : []
            return (
              <li key={s.id} className="card space-y-2">
                <div className="flex flex-wrap items-center gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="font-medium">{s.name}</div>
                    <div className="text-xs text-slate-500">
                      {s.targets.length > 0 ? `공유 대상: ${s.targets.map((t) => t.name).join(', ')}` : '공유 대상 없음(나만 봄)'}
                    </div>
                  </div>
                  {desktop && pending.length > 0 && (
                    <button className="btn" disabled={busy !== null || !folder.handle || !local} onClick={() => apply(s)}>내 PC 에 설치 ({pending.length}개)</button>
                  )}
                  <button className="btn" disabled={busy !== null || editing !== null} onClick={() => startEdit(s)}>수정</button>
                  <button className="btn btn-danger" disabled={busy !== null} onClick={() => remove(s)}>삭제</button>
                </div>
                {s.description && <p className="whitespace-pre-line text-sm text-slate-300">{s.description}</p>}
                <AddonChips setup={s} desktop={desktop} stateOf={stateOf} />
                {message[s.id] && <p className="whitespace-pre-line text-xs text-amber-300">{message[s.id]}</p>}
              </li>
            )
          })}
        </ul>
      </section>
    </div>
  )
}

function AddonChips({ setup, desktop, stateOf }: { setup: Setup; desktop: boolean; stateOf: (name: string) => LibraryState }) {
  return (
    <div className="space-y-1">
      <div className="flex flex-wrap gap-1.5">
        {setup.addons.map((n) => {
          const st = desktop ? STATE_LABEL[stateOf(n)] : null
          return (
            <span key={n} className={`rounded px-1.5 py-0.5 text-xs ${st ? st.cls : 'bg-slate-500/20 text-slate-300'}`} title={st?.label}>
              {n}
            </span>
          )
        })}
      </div>
      {setup.missingAddons.length > 0 && (
        <p className="text-xs text-amber-300">⚠ 자료실에서 사라져 설치할 수 없는 애드온: {setup.missingAddons.join(', ')}</p>
      )}
    </div>
  )
}

function SetupForm({ library, people, editing, installedNames, busy, onChange, onSave, onCancel }: {
  library: LibraryAddon[]
  people: { loginId: string; name: string }[]
  editing: { id: string | null; input: SetupInput }
  installedNames: string[]
  busy: boolean
  onChange: (input: SetupInput) => void
  onSave: () => void
  onCancel: () => void
}) {
  const { input } = editing
  const [query, setQuery] = useState('')
  const picked = useMemo(() => new Set(input.addons.map((a) => a.toLowerCase())), [input.addons])
  const q = query.trim().toLowerCase()
  const shown = library.filter((a) => !q || a.name.toLowerCase().includes(q))

  const toggleAddon = (name: string) =>
    onChange({ ...input, addons: picked.has(name.toLowerCase()) ? input.addons.filter((a) => a.toLowerCase() !== name.toLowerCase()) : [...input.addons, name] })
  const toggleTarget = (loginId: string) =>
    onChange({ ...input, targets: input.targets.includes(loginId) ? input.targets.filter((t) => t !== loginId) : [...input.targets, loginId] })
  const fillFromInstalled = () => {
    const libNames = new Map(library.map((a) => [a.name.toLowerCase(), a.name]))
    const mine = installedNames.map((n) => libNames.get(n.toLowerCase())).filter((n): n is string => !!n)
    onChange({ ...input, addons: Array.from(new Set([...input.addons, ...mine])) })
  }

  return (
    <div className="card space-y-3">
      <h2 className="font-semibold">{editing.id ? '세팅 수정' : '새 세팅'}</h2>
      <input className="input" placeholder="세팅 이름 (예: 레이드용 기본 세트)" maxLength={50} value={input.name} onChange={(e) => onChange({ ...input, name: e.target.value })} />
      <textarea className="input min-h-20" placeholder="설명 (누구에게 필요한 세팅인지, 어떤 용도인지)" maxLength={500} value={input.description} onChange={(e) => onChange({ ...input, description: e.target.value })} />

      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium">애드온 {input.addons.length}개 선택</span>
          {installedNames.length > 0 && <button className="btn !px-2 !py-0.5 text-xs" onClick={fillFromInstalled}>내 PC 에 설치된 것 담기</button>}
          {input.addons.length > 0 && <button className="btn !px-2 !py-0.5 text-xs" onClick={() => onChange({ ...input, addons: [] })}>모두 해제</button>}
          <input className="input ml-auto !w-48" placeholder="이름 검색" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <ul className="grid max-h-64 grid-cols-1 gap-1 overflow-y-auto rounded-md border border-slate-800 p-2 sm:grid-cols-2 lg:grid-cols-3">
          {shown.map((a) => (
            <li key={a.id}>
              <label className="flex cursor-pointer items-center gap-2 text-sm">
                <input type="checkbox" checked={picked.has(a.name.toLowerCase())} onChange={() => toggleAddon(a.name)} />
                <span className="truncate">{a.name}</span>
              </label>
            </li>
          ))}
          {shown.length === 0 && <li className="text-sm text-slate-500">검색 결과가 없습니다.</li>}
        </ul>
      </div>

      <div className="space-y-2">
        <span className="text-sm font-medium">공유할 사람 {input.targets.length}명 <span className="font-normal text-slate-500">(고르지 않으면 나만 봅니다)</span></span>
        <ul className="flex flex-wrap gap-x-4 gap-y-1">
          {people.map((p) => (
            <li key={p.loginId}>
              <label className="flex cursor-pointer items-center gap-2 text-sm">
                <input type="checkbox" checked={input.targets.includes(p.loginId)} onChange={() => toggleTarget(p.loginId)} />
                {p.name} <span className="text-xs text-slate-500">({p.loginId})</span>
              </label>
            </li>
          ))}
          {people.length === 0 && <li className="text-sm text-slate-500">공유할 수 있는 다른 사용자가 없습니다.</li>}
        </ul>
      </div>

      <div className="flex gap-2">
        <button className="btn btn-primary" disabled={busy || !input.name.trim() || input.addons.length === 0} onClick={onSave}>{busy ? '저장 중...' : '저장'}</button>
        <button className="btn" disabled={busy} onClick={onCancel}>취소</button>
      </div>
    </div>
  )
}
