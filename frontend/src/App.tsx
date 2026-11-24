import { Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import { useAuth } from './lib/AuthContext'
import { isDesktop } from './lib/nativeFs'
import AdminPage from './pages/AdminPage'
import AdminReleasePage from './pages/AdminReleasePage'
import DownloadPage from './pages/DownloadPage'
import FolderGate from './components/FolderGate'
import InstalledPage from './pages/InstalledPage'
import LoginPage from './pages/LoginPage'
import SearchPage from './pages/SearchPage'
import SignupPage from './pages/SignupPage'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { info } = useAuth()
  return info ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  // 데스크톱 앱: 폴더 자동 연결 후 설치·관리. 웹 브라우저: 검색과 앱 다운로드만(폴더 작업은 앱에서).
  const desktop = isDesktop()
  return (
    <Routes>
      {/* 로그인 전용 서비스라 소개 화면은 없다. 비로그인이면 RequireAuth 가 로그인 화면으로 보낸다. */}
      <Route path="/" element={<Navigate to={desktop ? '/installed' : '/search'} replace />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        {desktop ? (
          // 앱: 폴더 연결(자동 검색)이 끝난 뒤에만 열린다. 게임 환경은 이때 자동으로 만들어진다.
          <Route element={<FolderGate />}>
            <Route path="installed" element={<InstalledPage />} />
            <Route path="search" element={<SearchPage />} />
          </Route>
        ) : (
          <>
            <Route path="installed" element={<Navigate to="/search" replace />} />
            <Route path="search" element={<SearchPage />} />
          </>
        )}
        <Route path="download" element={<DownloadPage />} />
        <Route path="admin" element={<AdminPage />} />
        <Route path="admin/release" element={<AdminReleasePage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
