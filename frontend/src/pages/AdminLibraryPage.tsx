import { useCallback, useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useAuth } from '../lib/AuthContext'
import { formatAgo, formatBytes } from '../lib/format'
import { applyDeps, buildSyncPlan, uploadFolder, type SyncPlan, type SyncRow } from '../lib/librarySync'
import { getLibraryFolder, isDesktop, pickLibraryFolder } from '../lib/nativeFs'
import type { LibraryAddon } from '../lib/types'

const KIND_LABEL = { NEW: '새로 추가', CHANGED: '내용 바뀜', DEPS: '함께 설치 정보 갱신', SAME: '같음' } as const

export default function AdminLibraryPage() {
  const { isAdmin } = useAuth()
  const desktop = isDesktop()
  const qc = useQueryClient()
  const { data: library, isLoading, error } = useQuery({ queryKey: ['library'], queryFn: api.library, enabled: isAdmin })

  const [source, setSource] = useState<{ handle: FileSystemDirectoryHandle; label: string } | null>(null)
  const [plan, setPlan] = useState<SyncPlan | null>(null)
  const [upload, setUpload] = useState<Set<string>>(new Set())      // 올릴 폴더 이름
  const [remove, setRemove] = useState<Set<string>>(new Set())      // 서버에서 지울 항목 ID
  const [note, setNote] = useState('')
  const [status, setStatus] = useState<string | null>(null)         // 작업 중이면 진행 메시지
  const [error2, setError2] = useState<string | null>(null)
  const [report, setReport] = useState<string[]>([])

  // 이미 정해 둔 원본 폴더가 있으면 불러온다(앱에서만)
  useEffect(() => {
    if (!desktop || !isAdmin) return
    getLibraryFolder().then(setSource).catch(() => undefined)
  }, [desktop, isAdmin])

  const refreshLibrary = useCallback(() => qc.invalidateQueries({ queryKey: ['library'] }), [qc])

  // 메뉴를 숨기는 것은 화면 표시일 뿐이다. 실제 권한 검사는 서버(@PreAuthorize)가 한다.
  if (!isAdmin) return <Navigate to="/" replace />

  const busy = status !== null

  const chooseSource = async () => {
    setError2(null)
    try {
      const picked = await pickLibraryFolder()
      if (picked) {
        setSource(picked)
        setPlan(null)
      }
    } catch (e) {
      setError2((e as Error).message)
    }
  }

  const makePlan = async () => {
    if (!source || !library) return
    setError2(null)
    setReport([])
    setStatus('원본 폴더를 읽는 중...')
    try {
      const p = await buildSyncPlan(source.handle, library, setStatus)
      setPlan(p)
      // 새로 생겼거나 바뀐 것은 기본으로 선택하고, 원본에서 사라진 것은 실수로 지우지 않도록 선택하지 않는다
      setUpload(new Set(p.rows.filter((r) => r.kind !== 'SAME').map((r) => r.name)))
      setRemove(new Set())
    } catch (e) {
      setError2((e as Error).message)
    } finally {
      setStatus(null)
    }
  }

  const runSync = async () => {
    if (!source || !plan) return
    const targets = plan.rows.filter((r) => upload.has(r.name))
    const removals = plan.removed.filter((a) => remove.has(a.id))
    if (targets.length === 0 && removals.length === 0) return
    if (removals.length > 0 && !confirm(`서버 자료실에서 ${removals.length}개를 삭제합니다.\n${removals.map((a) => a.name).join(', ')}\n사용자가 이미 설치한 폴더는 그대로 남습니다. 계속할까요?`)) return

    setError2(null)
    const lines: string[] = []
    const done = new Set<string>()
    try {
      for (const [i, row] of targets.entries()) {
        setStatus(`올리는 중 (${i + 1}/${targets.length}) ${row.name}`)
        try {
          // 내용이 같고 함께 설치할 애드온 정보만 바뀐 항목은 다시 올리지 않는다
          const saved = row.kind === 'DEPS'
            ? library?.find((a) => a.name.toLowerCase() === row.name.toLowerCase())
            : await uploadFolder(source.handle, row, note.trim(), setStatus)
          if (!saved) throw new Error('서버 자료실에서 항목을 찾지 못했습니다.')
          await applyDeps(saved, row.deps)
          done.add(row.name)
        } catch (e) {
          lines.push(`✖ ${row.name}: ${(e as Error).message}`)
        }
      }
      for (const a of removals) {
        setStatus(`서버에서 지우는 중 ${a.name}`)
        try {
          await api.adminDeleteLibrary(a.id)
        } catch (e) {
          lines.push(`✖ ${a.name} 삭제: ${(e as Error).message}`)
        }
      }
      lines.unshift(`올림 ${done.size}개${removals.length > 0 ? `, 삭제 ${removals.length}개 시도` : ''}${lines.length > 0 ? `, 실패 ${lines.length}개` : ''}`)
      setReport(lines)
      await refreshLibrary()
      // 올린 것은 "같음"으로 바꿔 두고 사라진 항목은 계획에서 뺀다(다시 계획을 만들면 서버 기준으로 새로 계산된다)
      setPlan((prev) => prev && {
        rows: prev.rows.map((r): SyncRow => (done.has(r.name) ? { ...r, kind: 'SAME' } : r)),
        removed: prev.removed.filter((a) => !remove.has(a.id)),
      })
      setUpload(new Set())
      setRemove(new Set())
    } finally {
      setStatus(null)
    }
  }

  const toggle = <T,>(set: Set<T>, value: T, apply: (s: Set<T>) => void) => {
    const next = new Set(set)
    if (next.has(value)) next.delete(value)
    else next.add(value)
    apply(next)
  }

  /** 체크하면 함께 설치될 애드온(올릴 것이 있는 것)도 같이 체크한다. 따로 올리지 않으면 사용자가 설치할 때 없는 애드온이 된다. */
  const toggleRow = (row: SyncRow) => {
    const next = new Set(upload)
    if (next.has(row.name)) {
      next.delete(row.name)
    } else {
      next.add(row.name)
      const pending = new Set((plan?.rows ?? []).filter((r) => r.kind !== 'SAME').map((r) => r.name.toLowerCase()))
      for (const d of row.deps) {
        const found = plan?.rows.find((r) => r.name.toLowerCase() === d.toLowerCase())
        if (found && pending.has(found.name.toLowerCase())) next.add(found.name)
      }
    }
    setUpload(next)
  }

  const counts = plan ? {
    add: plan.rows.filter((r) => r.kind === 'NEW').length,
    changed: plan.rows.filter((r) => r.kind === 'CHANGED').length,
    deps: plan.rows.filter((r) => r.kind === 'DEPS').length,
    same: plan.rows.filter((r) => r.kind === 'SAME').length,
  } : null

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <div>
        <h1 className="text-xl font-bold">자료실 관리</h1>
        <p className="text-sm text-slate-400">
          애드온을 모아 둔 폴더를 자료실과 맞춥니다(동기화). 폴더 안의 애드온을 하루에 한 번 갱신한 뒤 "동기화"를 하면
          <b> 새로 생겼거나 바뀐 것만</b> 올라가고, 사용자는 자료실에서 설치·업데이트합니다.
        </p>
      </div>

      {error2 && <p className="text-sm text-rose-400">{error2}</p>}
      {status && <p className="text-sm text-indigo-300">{status}</p>}

      {desktop ? (
        <div className="card space-y-3">
          <h2 className="font-medium">원본 폴더에서 동기화</h2>
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span className="text-slate-400">원본 폴더:</span>
            <span className="min-w-0 flex-1 truncate">{source ? source.label : '아직 정하지 않았습니다'}</span>
            <button className="btn" onClick={chooseSource} disabled={busy}>{source ? '폴더 변경' : '폴더 선택'}</button>
          </div>
          <p className="text-xs text-slate-500">
            애드온 폴더들이 들어 있는 폴더를 고르세요(예: 애드온을 설치·업데이트해 두는 AddOns 폴더). 이 앱은 원본 폴더를 <b>읽기만</b> 하며
            아무것도 바꾸거나 지우지 않습니다.
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <input className="input min-w-48 flex-1" placeholder="올릴 때 남길 메모 (선택, 예: 출처·허락 여부)" maxLength={500}
                   value={note} onChange={(e) => setNote(e.target.value)} disabled={busy} />
            <button className="btn btn-primary" onClick={makePlan} disabled={busy || !source || !library}>동기화 계획 만들기</button>
          </div>

          {plan && counts && (
            <div className="space-y-3">
              <p className="text-sm text-slate-300">
                새로 추가 <b>{counts.add}</b> · 내용 바뀜 <b>{counts.changed}</b> · 함께 설치 정보 갱신 <b>{counts.deps}</b> · 같음 {counts.same} · 원본에서 사라짐 <b>{plan.removed.length}</b>
              </p>
              <ul className="max-h-96 space-y-1 overflow-auto">
                {groupRows(plan.rows.filter((r) => r.kind !== 'SAME')).map(({ row: r, child }) => (
                  <li key={r.name} className={`flex items-center gap-2 text-sm ${child ? 'ml-6' : ''}`}>
                    <input type="checkbox" checked={upload.has(r.name)} disabled={busy} onChange={() => toggleRow(r)} />
                    <span className="min-w-0 flex-1 truncate">
                      {child && <span className="text-slate-600">└ </span>}
                      {r.name}{r.version && <span className="text-xs text-slate-500"> v{r.version}</span>}
                      {!child && r.deps.length > 0 && <span className="text-xs text-indigo-300"> · 함께 설치: {r.deps.join(', ')}</span>}
                    </span>
                    <span className={`rounded px-1.5 py-0.5 text-xs ${r.kind === 'NEW' ? 'bg-emerald-500/15 text-emerald-300' : 'bg-amber-500/15 text-amber-300'}`}>{KIND_LABEL[r.kind]}</span>
                  </li>
                ))}
                {plan.removed.map((a) => (
                  <li key={a.id} className="flex items-center gap-2 text-sm">
                    <input type="checkbox" checked={remove.has(a.id)} disabled={busy} onChange={() => toggle(remove, a.id, setRemove)} />
                    <span className="min-w-0 flex-1 truncate">{a.name}</span>
                    <span className="rounded bg-rose-500/15 px-1.5 py-0.5 text-xs text-rose-300">원본에서 사라짐 · 서버에서 삭제</span>
                  </li>
                ))}
                {counts.add + counts.changed + counts.deps + plan.removed.length === 0 && (
                  <li className="text-sm text-slate-400">원본 폴더와 자료실이 같습니다. 할 일이 없습니다.</li>
                )}
              </ul>
              <button className="btn btn-primary" onClick={runSync} disabled={busy || (upload.size === 0 && remove.size === 0)}>
                선택한 항목 동기화 ({upload.size + remove.size})
              </button>
            </div>
          )}
          {report.length > 0 && <pre className="whitespace-pre-wrap rounded-md bg-slate-900/70 p-3 text-xs text-slate-300">{report.join('\n')}</pre>}
        </div>
      ) : (
        <p className="rounded-md border border-indigo-500/30 bg-indigo-500/10 p-3 text-sm text-indigo-200">
          원본 폴더 동기화는 PC 의 폴더를 읽어야 해서 <b>데스크톱 앱</b>에서만 할 수 있습니다. 웹에서는 올라온 목록을 관리합니다.
        </p>
      )}

      <div className="card space-y-2">
        <h2 className="font-medium">서버 자료실 ({library?.length ?? 0}개)</h2>
        {isLoading && <p className="text-sm text-slate-400">불러오는 중...</p>}
        {error && <p className="text-sm text-rose-400">{(error as Error).message}</p>}
        {library && library.length === 0 && <p className="text-sm text-slate-400">아직 올라온 애드온이 없습니다.</p>}
        <ul className="space-y-2">
          {library?.map((a) => <ServerRow key={a.id} addon={a} onChanged={refreshLibrary} />)}
        </ul>
      </div>
    </div>
  )
}

/**
 * 함께 설치될 애드온을 그것을 필요로 하는 애드온 바로 아래에 들여써서 묶어 보여 준다.
 * 어느 애드온의 의존성으로도 쓰이지 않는 것(또는 필요로 하는 쪽이 목록에 없는 것)은 맨 위 항목으로 둔다.
 */
function groupRows(rows: SyncRow[]): { row: SyncRow; child: boolean }[] {
  const byName = new Map(rows.map((r) => [r.name.toLowerCase(), r]))
  const placed = new Set<string>()
  const out: { row: SyncRow; child: boolean }[] = []
  // 의존성이 많은 쪽(묶음의 대표)을 먼저 둔다
  for (const r of [...rows].sort((a, b) => b.deps.length - a.deps.length || a.name.localeCompare(b.name))) {
    if (placed.has(r.name.toLowerCase())) continue
    placed.add(r.name.toLowerCase())
    out.push({ row: r, child: false })
    for (const d of r.deps) {
      const dep = byName.get(d.toLowerCase())
      if (dep && !placed.has(dep.name.toLowerCase())) {
        placed.add(dep.name.toLowerCase())
        out.push({ row: dep, child: true })
      }
    }
  }
  return out
}

/** 서버에 올라온 항목 한 줄: 같이 설치할 애드온 지정, 삭제. */
function ServerRow({ addon, onChanged }: { addon: LibraryAddon; onChanged: () => void }) {
  const [text, setText] = useState(addon.extraRequires.join(', '))
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const dirty = text.split(',').map((s) => s.trim()).filter(Boolean).join(',') !== addon.extraRequires.join(',')

  const save = async () => {
    setBusy(true)
    setMsg(null)
    try {
      const names = text.split(',').map((s) => s.trim()).filter(Boolean)
      await api.adminSetLibraryRequires(addon.id, names)
      setMsg({ ok: true, text: '저장했습니다' })
      onChanged()
    } catch (e) {
      setMsg({ ok: false, text: (e as Error).message })
    } finally {
      setBusy(false)
    }
  }

  const del = async () => {
    if (!confirm(`'${addon.name}' 을(를) 자료실에서 삭제할까요?\n이미 설치한 사용자의 폴더는 그대로 남습니다.`)) return
    setBusy(true)
    try {
      await api.adminDeleteLibrary(addon.id)
      onChanged()
    } catch (e) {
      setMsg({ ok: false, text: (e as Error).message })
      setBusy(false)
    }
  }

  return (
    <li className="space-y-1 rounded-md border border-slate-800 p-2">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="min-w-0 flex-1 truncate font-medium">
          {addon.name}{addon.version && <span className="text-xs font-normal text-slate-500"> v{addon.version}</span>}
        </span>
        <span className="text-xs text-slate-500">{formatBytes(addon.fileSize)}{addon.updatedAt && <> · {formatAgo(addon.updatedAt)}</>}</span>
        <button className="btn btn-danger !px-2 !py-0.5" disabled={busy} onClick={del}>삭제</button>
      </div>
      {addon.requires.length > addon.extraRequires.length && (
        <p className="text-xs text-slate-500">.toc 가 요구: {addon.requires.filter((r) => !addon.extraRequires.includes(r)).join(', ')}</p>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <input className="input min-w-48 flex-1 !py-1 text-xs" placeholder="같이 설치할 애드온 폴더 이름(쉼표로 구분, 예: QuestieDB, QuestieTrace)"
               value={text} onChange={(e) => setText(e.target.value)} disabled={busy} />
        <button className="btn !px-2 !py-0.5" disabled={busy || !dirty} onClick={save}>저장</button>
      </div>
      {msg && <p className={`text-xs ${msg.ok ? 'text-emerald-400' : 'text-rose-400'}`}>{msg.text}</p>}
    </li>
  )
}
