import { Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import { useAuth } from './lib/AuthContext'
import AdminPage from './pages/AdminPage'
import EnvironmentsPage from './pages/EnvironmentsPage'
import InstalledPage from './pages/InstalledPage'
import LandingPage from './pages/LandingPage'
import LoginPage from './pages/LoginPage'
import SearchPage from './pages/SearchPage'
import SignupPage from './pages/SignupPage'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { info } = useAuth()
  return info ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route path="installed" element={<InstalledPage />} />
        <Route path="search" element={<SearchPage />} />
        <Route path="environments" element={<EnvironmentsPage />} />
        <Route path="admin" element={<AdminPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
