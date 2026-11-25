import { NavLink, Outlet } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useAuth } from '../lib/AuthContext'
import { EnvProvider, useEnv } from '../lib/EnvContext'
import { useEffect, useState } from 'react'
import { checkAppUpdate, getAppVersion, installAppUpdate, isDesktop } from '../lib/nativeFs'

const navCls = ({ isActive }: { isActive: boolean }) =>
  `rounded-md px-3 py-1.5 text-sm font-medium transition ${
    isActive ? 'bg-slate-800 text-white' : 'text-slate-400 hover:text-white'
  }`

/** 앱 버전과 업데이트 확인(앱에서만). 옛 앱은 버전 조회가 없어서 그 사실을 알려 준다. */
function AppVersion() {
  const [version, setVersion] = useState<string | null | undefined>(undefined)   // undefined: 확인 중
  const [text, setText] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    getAppVersion().then(setVersion).catch(() => setVersion(null))
  }, [])

  const check = async () => {
    setBusy(true)
    setText(null)
    try {
      const r = await checkAppUpdate()
      if (!r) setText('이 앱은 업데이트 확인 기능이 없는 옛 버전입니다. 새 설치 파일로 다시 설치해 주세요.')
      else if (r.state === 'dev') setText('개발 실행 중이라 확인하지 않습니다.')
      else if (r.state === 'error') setText(`확인하지 못했습니다: ${r.message ?? '알 수 없는 오류'}`)
      else if (r.state === 'latest') setText(`최신 버전입니다.${r.latest ? ` (서버 ${r.latest})` : ''}`)
      else { setText(`새 버전 ${r.latest}을(를) 받았습니다.`); setReady(true) }
    } catch (e) {
      setText((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex items-center gap-2 text-sm text-slate-400">
      <span title="설치된 데스크톱 앱의 버전">{version === undefined ? '앱 확인 중...' : version ? `앱 v${version}` : '앱 버전 확인 불가(옛 버전)'}</span>
      <button className="btn !px-2 !py-0.5 text-xs" disabled={busy} onClick={check}>{busy ? '확인 중...' : '업데이트 확인'}</button>
      {ready && <button className="btn btn-primary !px-2 !py-0.5 text-xs" onClick={() => installAppUpdate()}>재시작해서 적용</button>}
      {text && <span className="text-xs text-amber-300">{text}</span>}
    </div>
  )
}

function Header() {
  const { info, isAdmin, logout } = useAuth()
  const { status, current, changeFolder } = useEnv()
  const desktop = isDesktop()
  // 받은 세팅 개수(새로 받은 것을 알아채도록 메뉴에 표시한다)
  const { data: setups } = useQuery({ queryKey: ['setups'], queryFn: api.setups })
  const receivedCount = setups?.received.length ?? 0
  // 앱에서는 와우 폴더 연결이 끝나기 전에 애드온 메뉴를 보여 주지 않는다
  const ready = status === 'ready'

  const onChangeFolder = () => changeFolder().catch((e) => alert((e as Error).message))

  return (
    <header className="border-b border-slate-800 bg-slate-900/80 backdrop-blur">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-3 px-4 py-3">
        <span className="mr-2 text-lg font-bold text-indigo-400">Addon Manager</span>
        <nav className="flex flex-wrap gap-1">
          {(!desktop || ready) && <NavLink to="/library" className={navCls}>자료실</NavLink>}
          {(!desktop || ready) && (
            <NavLink to="/setups" className={navCls}>
              세팅 공유{receivedCount > 0 && <span className="ml-1.5 rounded-full bg-indigo-500 px-1.5 py-0.5 text-xs text-white">{receivedCount}</span>}
            </NavLink>
          )}
          {!desktop && <NavLink to="/download" className={navCls}>앱 다운로드</NavLink>}
          {isAdmin && <NavLink to="/admin/library" className={navCls}>자료실 관리</NavLink>}
          {isAdmin && <NavLink to="/admin/release" className={navCls}>앱 배포</NavLink>}
          {isAdmin && <NavLink to="/admin" end className={navCls}>계정 승인</NavLink>}
        </nav>
        <div className="ml-auto flex items-center gap-3">
          {desktop && current && (
            <>
              <span className="text-sm text-slate-400">게임 버전 {current.gameVersion}</span>
              <button className="btn" onClick={onChangeFolder} title="다른 와우 설치 폴더로 바꿉니다">폴더 변경</button>
            </>
          )}
          {desktop && <AppVersion />}
          <span className="text-sm text-slate-400">{info?.loginId}</span>
          <button className="btn" onClick={logout}>로그아웃</button>
        </div>
      </div>
    </header>
  )
}

export default function Layout() {
  return (
    <EnvProvider>
      <div className="min-h-full">
        <Header />
        <main className="mx-auto max-w-6xl px-4 py-6">
          <Outlet />
        </main>
      </div>
    </EnvProvider>
  )
}
