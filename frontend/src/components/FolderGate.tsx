import { useEffect, useRef, useState } from 'react'
import { Outlet } from 'react-router-dom'
import { useEnv } from '../lib/EnvContext'
import { isFolderAccessSupported } from '../lib/handles'
import { detectFolders, handleFromKey, isDesktop, type DetectedFolder } from '../lib/nativeFs'

/**
 * 애드온 화면(설치된 애드온·검색)의 입구. 폴더 연결이 끝난 뒤에만 화면을 보여 준다.
 * - 데스크톱 앱: PC 에서 AddOns 폴더를 자동으로 찾아 연결한다(한 곳이면 클릭 없이).
 * - 웹 브라우저: 처음 한 번 폴더를 직접 선택한다. 환경 생성·게임 버전 설정은 둘 다 자동이다.
 */
export default function FolderGate() {
  const { status, connect, grantAccess } = useEnv()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // undefined: 검색 중, 배열: 검색 결과(데스크톱 앱에서만 사용)
  const [detected, setDetected] = useState<DetectedFolder[] | undefined>(undefined)
  const detectStarted = useRef(false)

  const run = async (fn: () => Promise<void>) => {
    setError(null)
    setBusy(true)
    try {
      await fn()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const connectDetected = (folder: DetectedFolder) => run(() => connect(handleFromKey(folder.key)))

  // 데스크톱 앱이면 폴더가 필요할 때 한 번만 자동 검색하고, 한 곳만 찾았으면 바로 연결한다
  useEffect(() => {
    if (status !== 'needsFolder' || !isDesktop() || detectStarted.current) return
    detectStarted.current = true
    detectFolders()
      .then((found) => {
        setDetected(found)
        if (found.length === 1) void connectDetected(found[0])
      })
      .catch((e: Error) => {
        setDetected([])
        setError(e.message)
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status])

  if (status === 'loading') return <p className="text-slate-400">불러오는 중...</p>
  if (status === 'ready') return <Outlet />

  if (!isFolderAccessSupported()) {
    return (
      <div className="card text-center text-slate-300">
        이 브라우저는 로컬 폴더 접근을 지원하지 않습니다. Chrome 또는 Edge 에서 접속하거나 데스크톱 앱을 설치해 주세요.
      </div>
    )
  }

  if (status === 'needsFolder' && isDesktop()) {
    const searching = detected === undefined
    return (
      <div className="card mx-auto max-w-xl space-y-3 text-center">
        <h1 className="text-lg font-bold">와우 폴더를 연결합니다</h1>
        {searching && <p className="text-sm text-slate-400">PC 에서 와우 설치 폴더를 찾는 중...</p>}
        {!searching && detected.length === 1 && busy && (
          <p className="text-sm text-slate-400">찾았습니다. 연결하는 중...</p>
        )}
        {!searching && detected.length > 1 && (
          <>
            <p className="text-sm text-slate-400">여러 개를 찾았습니다. 사용할 폴더를 선택하세요.</p>
            <ul className="space-y-2">
              {detected.map((f) => (
                <li key={f.key}>
                  <button className="btn w-full justify-start break-all text-left" disabled={busy} onClick={() => connectDetected(f)}>
                    {f.label}
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
        {!searching && detected.length === 0 && (
          <p className="text-sm text-slate-400">자동으로 찾지 못했습니다. 와우 설치 폴더를 직접 선택해 주세요.</p>
        )}
        {!searching && !(detected.length === 1 && busy) && (
          <button className="btn" disabled={busy} onClick={() => run(() => connect())}>
            다른 폴더 직접 선택
          </button>
        )}
        {error && <p className="text-sm text-rose-400">{error}</p>}
      </div>
    )
  }

  return (
    <div className="card mx-auto max-w-xl space-y-3 text-center">
      {status === 'needsFolder' ? (
        <>
          <h1 className="text-lg font-bold">와우 폴더를 선택해 주세요</h1>
          <p className="text-sm text-slate-400">
            처음 한 번만 선택하면 됩니다. 이후에는 로그인하는 즉시 설치된 애드온과 업데이트 상태가 자동으로 표시됩니다.
          </p>
          <p className="text-sm text-slate-400">
            <b>World of Warcraft</b> 설치 폴더(또는 Interface\AddOns 폴더)를 선택하세요.
            게임 버전은 설치된 애드온을 보고 자동으로 설정됩니다.
          </p>
          {/* Chrome 은 시스템 폴더를 보호하려고 일부 폴더를 선택하지 못하게 막는다. 이유를 알려 주지 않으면 막막하다. */}
          <p className="rounded-md border border-amber-600/40 bg-amber-500/10 p-2 text-xs text-amber-200">
            "시스템 파일이 포함되어 열 수 없음" 창이 뜨면 Chrome 이 그 위치를 막은 것입니다.
            Program Files, Windows, AppData 와 그 <b>하위 폴더 전체</b>, 드라이브 루트(C:\ 등)는 선택할 수 없습니다.
            게임이 그 안에 있다면 폴더를 <b>C:\Games\World of Warcraft</b> 처럼 다른 곳으로 옮긴 뒤 선택하거나,
            데스크톱 앱을 설치해 사용하세요.
          </p>
          <button className="btn btn-primary" disabled={busy} onClick={() => run(() => connect())}>
            {busy ? '확인 중...' : '와우 폴더 선택'}
          </button>
        </>
      ) : (
        <>
          <h1 className="text-lg font-bold">폴더 접근을 허용해 주세요</h1>
          <p className="text-sm text-slate-400">
            브라우저를 다시 열면 보안상 폴더 접근 권한을 한 번 더 확인합니다. 허용하면 바로 스캔을 시작합니다.
          </p>
          <button className="btn btn-primary" disabled={busy} onClick={() => run(grantAccess)}>
            폴더 접근 허용
          </button>
        </>
      )}
      {error && <p className="text-sm text-rose-400">{error}</p>}
    </div>
  )
}
