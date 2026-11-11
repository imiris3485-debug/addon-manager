import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/AuthContext'

export default function LoginPage() {
  const { info, login } = useAuth()
  const navigate = useNavigate()
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (info) return <Navigate to="/" replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(loginId, password)
      navigate('/', { replace: true })
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-full items-center justify-center px-4">
      <form onSubmit={submit} className="card w-full max-w-sm space-y-4">
        <h1 className="text-xl font-bold text-indigo-400">Addon Manager</h1>
        <p className="text-sm text-slate-400">로그인하여 애드온을 관리하세요.</p>
        <input className="input" placeholder="아이디" autoComplete="username" value={loginId}
               onChange={(e) => setLoginId(e.target.value)} required />
        <input className="input" type="password" placeholder="비밀번호" autoComplete="current-password"
               value={password} onChange={(e) => setPassword(e.target.value)} required />
        {error && <p className="text-sm text-rose-400">{error}</p>}
        <button className="btn btn-primary w-full" disabled={busy}>{busy ? '로그인 중...' : '로그인'}</button>
        <p className="text-center text-sm text-slate-400">
          계정이 없나요? <Link className="text-indigo-400 hover:underline" to="/signup">가입 요청</Link>
        </p>
      </form>
    </div>
  )
}
