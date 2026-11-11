import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from './api'
import type { Environment } from './types'

interface EnvState {
  environments: Environment[]
  loading: boolean
  current: Environment | null
  setCurrentId: (id: number) => void
}

const EnvContext = createContext<EnvState | null>(null)
const KEY = 'current-environment'

function readSaved(): number | null {
  try {
    const v = Number(localStorage.getItem(KEY))
    return Number.isInteger(v) && v > 0 ? v : null
  } catch {
    return null
  }
}

export function EnvProvider({ children }: { children: ReactNode }) {
  const { data, isLoading } = useQuery({ queryKey: ['environments'], queryFn: api.environments })
  const [currentId, setId] = useState<number | null>(readSaved())

  const environments = data ?? []
  // 저장된 환경이 없거나 삭제되었으면 첫 번째 환경을 선택한다
  const current = environments.find((e) => e.id === currentId) ?? environments[0] ?? null

  useEffect(() => {
    if (current) {
      try {
        localStorage.setItem(KEY, String(current.id))
      } catch {
        /* 저장소를 쓸 수 없으면 무시 */
      }
    }
  }, [current])

  const value = useMemo<EnvState>(
    () => ({ environments, loading: isLoading, current, setCurrentId: setId }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [data, isLoading, current],
  )
  return <EnvContext.Provider value={value}>{children}</EnvContext.Provider>
}

export function useEnv(): EnvState {
  const ctx = useContext(EnvContext)
  if (!ctx) throw new Error('EnvProvider 안에서 사용해야 합니다.')
  return ctx
}
