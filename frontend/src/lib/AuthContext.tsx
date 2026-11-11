import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { clearToken, getToken, readToken, setToken, type TokenInfo } from './auth'
import { api } from './api'

interface AuthState {
  info: TokenInfo | null
  isAdmin: boolean
  login: (loginId: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [token, setTokenState] = useState<string | null>(getToken())

  // 다른 탭의 로그아웃, 401 로 인한 토큰 삭제를 반영한다
  useEffect(() => {
    const sync = () => setTokenState(getToken())
    window.addEventListener('auth-changed', sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener('auth-changed', sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  const info = useMemo(() => readToken(token), [token])

  const login = useCallback(async (loginId: string, password: string) => {
    const res = await api.login(loginId, password)
    setToken(res.accessToken)
  }, [])

  const logout = useCallback(() => {
    clearToken()
    queryClient.clear()          // 이전 계정의 캐시가 남지 않게 한다
  }, [queryClient])

  const value = useMemo<AuthState>(
    () => ({ info, isAdmin: info?.roles.includes('ADMIN') ?? false, login, logout }),
    [info, login, logout],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('AuthProvider 안에서 사용해야 합니다.')
  return ctx
}
