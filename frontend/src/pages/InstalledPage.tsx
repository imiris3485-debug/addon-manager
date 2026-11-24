import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { StatusBadge } from '../components/StatusBadge'
import { useEnv } from '../lib/EnvContext'
import { hasPermission } from '../lib/handles'
import { installAddon, removeAddon } from '../lib/installer'
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

  const update = async (a: InstalledAddon, root: FileSystemDirectoryHandle) => {
    if (envId == null || a.latestFileId == null) return
    setRowState((s) => ({ ...s, [a.projectId]: '설치 중...' }))
    try {
      const res = await installAddon(envId, root, a.projectId, a.latestFileId)
      patchAddon(a.projectId, {
        status: 'UP_TO_DATE', installedFileId: a.latestFileId, installedVersion: res.tocVersion, folders: res.folders,
      })
      setRowState((s) => ({ ...s, [a.projectId]: '' }))
    } catch (e) {
      setRowState((s) => ({ ...s, [a.projectId]: `실패: ${(e as Error).message}` }))
      throw e
    }
  }

  const onUpdate = async (a: InstalledAddon) => {
    setError(null)
    try {
      await update(a, await folder.requireAccess())
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
      for (const [i, a] of targets.entries()) {
        setStatus(`업데이트 중 (${i + 1}/${targets.length}) ${a.name}`)
        try {
          await update(a, root)
        } catch {
          failed++
        }
      }
      if (failed > 0) setError(`${failed}개 애드온 업데이트에 실패했습니다. 목록의 오류 메시지를 확인하세요.`)
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
                  {rowState[a.projectId] && <div className="text-xs text-amber-300">{rowState[a.projectId]}</div>}
                </div>
                <div className="flex gap-2">
                  {(a.status === 'UPDATE_AVAILABLE' || a.status === 'UNKNOWN') && a.latestFileId != null && (
                    a.downloadable
                      ? <button className="btn btn-primary" disabled={busy} onClick={() => onUpdate(a)}>
                          {a.status === 'UNKNOWN' ? '최신으로 설치' : '업데이트'}
                        </button>
                      : a.websiteUrl
                        ? <a className="btn" href={a.websiteUrl} target="_blank" rel="noreferrer">CurseForge 에서 받기</a>
                        : null
                  )}
                  <button className="btn btn-danger" disabled={busy} onClick={() => onRemove(a)}>삭제</button>
                </div>
              </li>
            ))}
          </ul>

          {result.unmatchedFolders.length > 0 && (
            <details className="card">
              <summary className="cursor-pointer text-sm text-slate-300">
                CurseForge 와 연결되지 않은 폴더 {result.unmatchedFolders.length}개
              </summary>
              <p className="mt-2 text-xs text-slate-500">
                다른 경로로 설치했거나 라이브러리 폴더일 수 있습니다. 관리 대상에서 제외됩니다.
              </p>
              <p className="mt-2 text-xs text-slate-400">{result.unmatchedFolders.join(', ')}</p>
            </details>
          )}
        </>
      )}
    </div>
  )
}
