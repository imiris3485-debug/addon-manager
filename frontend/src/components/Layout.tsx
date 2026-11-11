import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../lib/AuthContext'
import { EnvProvider, useEnv } from '../lib/EnvContext'
import { isFolderAccessSupported } from '../lib/handles'

const navCls = ({ isActive }: { isActive: boolean }) =>
  `rounded-md px-3 py-1.5 text-sm font-medium transition ${
    isActive ? 'bg-slate-800 text-white' : 'text-slate-400 hover:text-white'
  }`

function Header() {
  const { info, isAdmin, logout } = useAuth()
  const { environments, current, setCurrentId } = useEnv()

  return (
    <header className="border-b border-slate-800 bg-slate-900/80 backdrop-blur">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-3 px-4 py-3">
        <span className="mr-2 text-lg font-bold text-indigo-400">Addon Manager</span>
        <nav className="flex flex-wrap gap-1">
          <NavLink to="/installed" className={navCls}>설치된 애드온</NavLink>
          <NavLink to="/search" className={navCls}>검색</NavLink>
          <NavLink to="/environments" className={navCls}>게임 환경</NavLink>
          {isAdmin && <NavLink to="/admin" className={navCls}>계정 승인</NavLink>}
        </nav>
        <div className="ml-auto flex items-center gap-3">
          {environments.length > 0 && (
            <select
              className="input !w-auto"
              value={current?.id ?? ''}
              onChange={(e) => setCurrentId(Number(e.target.value))}
              aria-label="게임 환경 선택"
            >
              {environments.map((e) => (
                <option key={e.id} value={e.id}>{e.name} ({e.gameVersion})</option>
              ))}
            </select>
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
          {!isFolderAccessSupported() && (
            <div className="mb-4 rounded-md border border-amber-600/40 bg-amber-500/10 p-3 text-sm text-amber-200">
              이 브라우저는 로컬 폴더 접근(File System Access API)을 지원하지 않습니다.
              애드온 설치·업데이트는 Chrome 또는 Edge 에서 사용해 주세요.
            </div>
          )}
          <Outlet />
        </main>
      </div>
    </EnvProvider>
  )
}
