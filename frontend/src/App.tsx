import { Navigate, Route, Routes } from 'react-router-dom'
import FolderGate from './components/FolderGate'
import Layout from './components/Layout'
import { useAuth } from './lib/AuthContext'
import { isDesktop } from './lib/nativeFs'
import AdminLibraryPage from './pages/AdminLibraryPage'
import AdminPage from './pages/AdminPage'
import AdminReleasePage from './pages/AdminReleasePage'
import DownloadPage from './pages/DownloadPage'
import LibraryPage from './pages/LibraryPage'
import LoginPage from './pages/LoginPage'
import SetupsPage from './pages/SetupsPage'
import SignupPage from './pages/SignupPage'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { info } = useAuth()
  return info ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  // 데스크톱 앱: 와우 AddOns 폴더를 연결한 뒤 자료실에서 설치·업데이트. 웹 브라우저: 자료실 목록 보기와 앱 다운로드만.
  const desktop = isDesktop()
  return (
    <Routes>
      {/* 로그인 전용 서비스라 소개 화면은 없다. 비로그인이면 RequireAuth 가 로그인 화면으로 보낸다. */}
      <Route path="/" element={<Navigate to="/library" replace />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        {desktop ? (
          // 앱: AddOns 폴더 연결(자동 검색)이 끝난 뒤에만 열린다. 게임 환경은 이때 자동으로 만들어진다.
          <Route element={<FolderGate />}>
            <Route path="library" element={<LibraryPage />} />
            <Route path="setups" element={<SetupsPage />} />
          </Route>
        ) : (
          <>
            <Route path="library" element={<LibraryPage />} />
            <Route path="setups" element={<SetupsPage />} />
          </>
        )}
        <Route path="download" element={<DownloadPage />} />
        <Route path="admin" element={<AdminPage />} />
        {/* 관리자 화면은 와우 폴더와 무관하다(원본 폴더를 따로 고른다). 폴더 연결 단계를 거치지 않는다. */}
        <Route path="admin/library" element={<AdminLibraryPage />} />
        <Route path="admin/release" element={<AdminReleasePage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
