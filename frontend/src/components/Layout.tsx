import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../lib/AuthContext'
import { EnvProvider, useEnv } from '../lib/EnvContext'
import { isDesktop } from '../lib/nativeFs'

const navCls = ({ isActive }: { isActive: boolean }) =>
  `rounded-md px-3 py-1.5 text-sm font-medium transition ${
    isActive ? 'bg-slate-800 text-white' : 'text-slate-400 hover:text-white'
  }`

function Header() {
  const { info, isAdmin, logout } = useAuth()
  const { status, current, changeFolder } = useEnv()
  const desktop = isDesktop()
  // 앱에서는 와우 폴더 연결이 끝나기 전에 애드온 메뉴를 보여 주지 않는다
  const ready = status === 'ready'

  const onChangeFolder = () => changeFolder().catch((e) => alert((e as Error).message))

  return (
    <header className="border-b border-slate-800 bg-slate-900/80 backdrop-blur">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-3 px-4 py-3">
        <span className="mr-2 text-lg font-bold text-indigo-400">Addon Manager</span>
        <nav className="flex flex-wrap gap-1">
          {desktop && ready && <NavLink to="/installed" className={navCls}>설치된 애드온</NavLink>}
          {(!desktop || ready) && <NavLink to="/search" className={navCls}>검색</NavLink>}
          {!desktop && <NavLink to="/download" className={navCls}>앱 다운로드</NavLink>}
          {isAdmin && <NavLink to="/admin" end className={navCls}>계정 승인</NavLink>}
          {isAdmin && <NavLink to="/admin/release" className={navCls}>앱 배포</NavLink>}
        </nav>
        <div className="ml-auto flex items-center gap-3">
          {desktop && current && (
            <>
              <span className="text-sm text-slate-400">게임 버전 {current.gameVersion}</span>
              <button className="btn" onClick={onChangeFolder} title="다른 와우 설치 폴더로 바꿉니다">폴더 변경</button>
            </>
          )}
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
