import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'

export default function SignupPage() {
  const [loginId, setLoginId] = useState('')
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.signup(loginId, password, name)
      setDone(true)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  if (done) {
    return (
      <div className="flex min-h-full items-center justify-center px-4">
        <div className="card w-full max-w-sm space-y-3 text-center">
          <h1 className="text-lg font-bold">가입 요청이 접수되었습니다</h1>
          <p className="text-sm text-slate-400">관리자가 승인하면 로그인할 수 있습니다.</p>
          <Link className="btn btn-primary" to="/login">로그인 화면으로</Link>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-full items-center justify-center px-4">
      <form onSubmit={submit} className="card w-full max-w-sm space-y-4">
        <h1 className="text-xl font-bold text-indigo-400">가입 요청</h1>
        <p className="text-sm text-slate-400">관리자 승인 후 사용할 수 있습니다.</p>
        <input className="input" placeholder="아이디 (영문·숫자, 4자 이상)" autoComplete="username"
               value={loginId} onChange={(e) => setLoginId(e.target.value)} required minLength={4} />
        <input className="input" placeholder="이름" value={name} onChange={(e) => setName(e.target.value)} required />
        <input className="input" type="password" placeholder="비밀번호 (8자 이상)" autoComplete="new-password"
               value={password} onChange={(e) => setPassword(e.target.value)} required minLength={8} />
        {error && <p className="text-sm text-rose-400">{error}</p>}
        <button className="btn btn-primary w-full" disabled={busy}>{busy ? '요청 중...' : '가입 요청'}</button>
        <p className="text-center text-sm text-slate-400">
          이미 계정이 있나요? <Link className="text-indigo-400 hover:underline" to="/login">로그인</Link>
        </p>
      </form>
    </div>
  )
}
