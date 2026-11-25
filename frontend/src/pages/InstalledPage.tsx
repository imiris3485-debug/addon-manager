import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Changelog } from '../components/Changelog'
import { StatusBadge } from '../components/StatusBadge'
import { curseforgeInstallUrl, curseforgeLinkAttrs } from '../lib/curseforgeLink'
import { useEnv } from '../lib/EnvContext'
import { hasPermission } from '../lib/handles'
import { describeDependencies, installWithDependencies, removeAddon, removeUnmanagedFolder } from '../lib/installer'
import { scanEnvironment } from '../lib/scanner'
import type { InstalledAddon, ScanResponse } from '../lib/types'
import { useFolder } from '../lib/useFolder'

export default function InstalledPage() {
  const { current } = useEnv()
  const envId = current?.id ?? null
  const qc = useQueryClient()
  const folder = useFolder(envId)

  // 마지막 스캔 결과는 쿼리 캐시에 두어 다른 화면(검색)에서도 "설치됨" 표시에 쓴다
  const { data: result } = useQuery<ScanResponse | null>({
    queryKey: ['scan', envId], queryFn: async () => null, enabled: false, staleTime: Infinity,
  })

  const [status, setStatus] = useState<string | null>(null)   // 진행 중 메시지(작업 중이면 값이 있음)
  const [error, setError] = useState<string | null>(null)
  const [rowState, setRowState] = useState<Record<number, string>>({})
  const [openChangelog, setOpenChangelog] = useState<number | null>(null)

  const setResult = useCallback(
    (updater: (prev: ScanResponse) => ScanResponse) =>
      qc.setQueryData<ScanResponse | null>(['scan', envId], (prev) => (prev ? updater(prev) : prev)),
    [qc, envId],
  )

  const runScan = useCallback(async () => {
    if (envId == null) return
    setError(null)
    try {
      const root = await folder.requireAccess()
      setStatus('스캔 준비 중...')
      const res = await scanEnvironment(envId, root, setStatus)
      qc.setQueryData(['scan', envId], res)
      // 스캔하면 서버의 설치 기록이 이 결과로 바뀌므로 검색 화면의 "설치됨" 표시도 갱신한다
      await qc.invalidateQueries({ queryKey: ['installed-ids', envId] })
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setStatus(null)
    }
  }, [envId, folder, qc])

  // 이미 권한이 허용된 상태라면(같은 세션) 화면을 열 때 자동으로 스캔한다
  const autoScanned = useRef<number | null>(null)
  useEffect(() => {
    if (!folder.handle || envId == null || result || autoScanned.current === envId) return
    autoScanned.current = envId
    hasPermission(folder.handle)
      .then((ok) => {
        if (ok) void runScan()
      })
      .catch(() => undefined)
  }, [folder.handle, envId, result, runScan])

  const patchAddon = (projectId: number, patch: Partial<InstalledAddon>) =>
    setResult((prev) => ({ ...prev, addons: prev.addons.map((a) => (a.projectId === projectId ? { ...a, ...patch } : a)) }))

  /** 업데이트하고, 함께 새로 설치한 필수 애드온 수를 돌려준다(있으면 목록을 다시 스캔해야 한다). */
  const update = async (a: InstalledAddon, root: FileSystemDirectoryHandle): Promise<number> => {
    if (envId == null || a.latestFileId == null) return 0
    const say = (text: string) => setRowState((s) => ({ ...s, [a.projectId]: text }))
    say('설치 중...')
    try {
      const installedIds = new Set(result?.addons.map((x) => x.projectId) ?? [])
      const { result: res, dependencies } = await installWithDependencies(
        envId, root, a.projectId, a.latestFileId, installedIds, say)
      patchAddon(a.projectId, {
        status: 'UP_TO_DATE', installedFileId: a.latestFileId, installedVersion: res.tocVersion, folders: res.folders,
      })
      say(describeDependencies(dependencies).join('\n'))
      return dependencies.installed.length
    } catch (e) {
      say(`실패: ${(e as Error).message}`)
      throw e
    }
  }

  const onUpdate = async (a: InstalledAddon) => {
    setError(null)
    try {
      const newDeps = await update(a, await folder.requireAccess())
      if (newDeps > 0) await runScan()        // 새로 깔린 필수 애드온을 목록에 반영한다
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const onUpdateAll = async () => {
    if (!result) return
    setError(null)
    try {
      const root = await folder.requireAccess()
      const targets = result.addons.filter((a) => a.status === 'UPDATE_AVAILABLE' && a.downloadable)
      let failed = 0
      let newDeps = 0
      for (const [i, a] of targets.entries()) {
        setStatus(`업데이트 중 (${i + 1}/${targets.length}) ${a.name}`)
        try {
          newDeps += await update(a, root)
        } catch {
          failed++
        }
      }
      if (failed > 0) setError(`${failed}개 애드온 업데이트에 실패했습니다. 목록의 오류 메시지를 확인하세요.`)
      if (newDeps > 0) await runScan()        // 새로 깔린 필수 애드온을 목록에 반영한다
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setStatus(null)
    }
  }

  const onRemove = async (a: InstalledAddon) => {
    if (envId == null || !confirm(`'${a.name}' 폴더(${a.folders.join(', ')})를 삭제할까요?`)) return
    setError(null)
    try {
      const root = await folder.requireAccess()
      await removeAddon(envId, root, a.projectId, a.folders)
      setResult((prev) => ({ ...prev, addons: prev.addons.filter((x) => x.projectId !== a.projectId) }))
      await qc.invalidateQueries({ queryKey: ['installed-ids', envId] })
    } catch (e) {
      setError((e as Error).message)
    }
  }

  /** 연결되지 않은 폴더 삭제. 설치 기록이 없어 폴더만 지우고 목록에서 뺀다. */
  const onRemoveUnmanaged = async (folderName: string) => {
    if (envId == null) return
    if (!confirm(`'${folderName}' 폴더를 삭제할까요?\nCurseForge 와 연결되지 않은 폴더라 되돌릴 수 없고, 다른 애드온이 이 폴더를 쓰고 있을 수 있습니다.`)) return
    setError(null)
    try {
      const root = await folder.requireAccess()
      await removeUnmanagedFolder(envId, root, folderName)
      setResult((prev) => ({ ...prev, unmatchedFolders: prev.unmatchedFolders.filter((x) => x !== folderName) }))
    } catch (e) {
      setError((e as Error).message)
    }
  }

  // 환경과 폴더 연결은 FolderGate 가 보장한다. 핸들을 읽어 오는 짧은 순간만 대기 표시를 한다.
  if (!current || folder.loading || !folder.handle) return <p className="text-slate-400">불러오는 중...</p>

  const updatable = result?.addons.filter((a) => a.status === 'UPDATE_AVAILABLE' && a.downloadable).length ?? 0
  const busy = status !== null

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="text-xl font-bold">설치된 애드온</h1>
        <span className="text-sm text-slate-400">{current.name} · 게임 버전 {current.gameVersion} · 폴더 {folder.handle.name}</span>
        <div className="ml-auto flex gap-2">
          <button className="btn" onClick={runScan} disabled={busy}>{result ? '다시 스캔' : '스캔'}</button>
          <button className="btn btn-primary" onClick={onUpdateAll} disabled={busy || updatable === 0}>
            모두 업데이트{updatable > 0 ? ` (${updatable})` : ''}
          </button>
        </div>
      </div>

      {status && <p className="text-sm text-indigo-300">{status}</p>}
      {error && <p className="text-sm text-rose-400">{error}</p>}

      {!result && !busy && (
        <div className="card text-center text-slate-400">
          [스캔]을 눌러 설치된 애드온과 업데이트 여부를 확인하세요. (처음에는 폴더 접근 권한 허용이 필요합니다)
        </div>
      )}

      {result && (
        <>
          {result.addons.length === 0 && <div className="card text-slate-400">CurseForge 와 연결된 애드온이 없습니다.</div>}
          <ul className="space-y-2">
            {result.addons.map((a) => (
              <li key={a.projectId} className="card flex flex-wrap items-center gap-3">
                {a.logoUrl
                  ? <img src={a.logoUrl} alt="" className="h-10 w-10 rounded" />
                  : <div className="h-10 w-10 rounded bg-slate-800" />}
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{a.name}</span>
                    <StatusBadge status={a.status} />
                  </div>
                  <div className="truncate text-xs text-slate-400">
                    설치 {a.installedVersion ?? '알 수 없음'}
                    {a.latestFileName && <> · 최신 {a.latestFileName}</>}
                    {' · '}{a.folders.join(', ')}
                  </div>
                  {rowState[a.projectId] && <div className="whitespace-pre-line text-xs text-amber-300">{rowState[a.projectId]}</div>}
                </div>
                <div className="flex gap-2">
                  {/* 업데이트하기 전에 무엇이 바뀌는지 볼 수 있게 한다 */}
                  {a.status === 'UPDATE_AVAILABLE' && a.latestFileId != null && (
                    <button className="btn" onClick={() => setOpenChangelog(openChangelog === a.projectId ? null : a.projectId)}>
                      {openChangelog === a.projectId ? '변경 내역 접기' : '변경 내역'}
                    </button>
                  )}
                  {(a.status === 'UPDATE_AVAILABLE' || a.status === 'UNKNOWN') && a.latestFileId != null && (
                    a.downloadable
                      ? <button className="btn btn-primary" disabled={busy} onClick={() => onUpdate(a)}>
                          {a.status === 'UNKNOWN' ? '최신으로 설치' : '업데이트'}
                        </button>
                      : <a className="btn" href={curseforgeInstallUrl(a.projectId, a.latestFileId)} {...curseforgeLinkAttrs()}
                           title="작성자가 외부 배포를 막았습니다. CurseForge 앱이 설치돼 있으면 그 앱으로 설치합니다">
                          CurseForge 앱으로 업데이트
                        </a>
                  )}
                  {!a.downloadable && a.websiteUrl && (
                    <a className="btn" href={a.websiteUrl} target="_blank" rel="noreferrer">CurseForge 에서 받기</a>
                  )}
                  <button className="btn btn-danger" disabled={busy} onClick={() => onRemove(a)}>삭제</button>
                </div>
                {openChangelog === a.projectId && a.latestFileId != null && (
                  <div className="w-full"><Changelog projectId={a.projectId} fileId={a.latestFileId} /></div>
                )}
              </li>
            ))}
          </ul>

          {result.unmatchedFolders.length > 0 && (
            <details className="card">
              <summary className="cursor-pointer text-sm text-slate-300">
                CurseForge 와 연결되지 않은 폴더 {result.unmatchedFolders.length}개
              </summary>
              <p className="mt-2 text-xs text-slate-500">
                다른 경로로 설치했거나 라이브러리 폴더일 수 있습니다. 업데이트 관리 대상에서 제외됩니다.
                다른 애드온이 쓰는 폴더일 수 있으니 무엇인지 확인한 뒤 삭제하세요.
              </p>
              <ul className="mt-2 space-y-1">
                {result.unmatchedFolders.map((f) => (
                  <li key={f} className="flex items-center gap-2 text-xs text-slate-300">
                    <span className="min-w-0 flex-1 truncate">{f}</span>
                    <button className="btn btn-danger !px-2 !py-0.5" disabled={busy} onClick={() => onRemoveUnmanaged(f)}>삭제</button>
                  </li>
                ))}
              </ul>
            </details>
          )}
        </>
      )}
    </div>
  )
}
