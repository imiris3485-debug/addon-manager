import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import { detectGameVersion, resolveAddonsFolder } from './folderSetup'
import { ensurePermission, hasPermission, loadHandle, pickAddonsFolder, saveHandle } from './handles'
import type { Environment } from './types'

/**
 * 게임 환경은 사용자가 만들지 않는다. 이 브라우저에 폴더가 연결된 환경을 자동으로 고르고,
 * 없으면 폴더를 한 번 선택하는 순간 환경을 만든다(브라우저는 PC 경로를 스스로 찾을 수 없어 이 한 번은 필요).
 *
 * - loading         : 서버/브라우저 저장소를 확인하는 중
 * - needsFolder     : 이 브라우저에 연결된 폴더가 없다(첫 접속, 다른 PC)
 * - needsPermission : 폴더는 기억하고 있으나 이번 세션의 접근 권한이 아직 없다(클릭 한 번 필요)
 * - ready           : 바로 쓸 수 있다
 */
export type EnvStatus = 'loading' | 'needsFolder' | 'needsPermission' | 'ready'

interface EnvState {
  status: EnvStatus
  current: Environment | null
  /**
   * 이 브라우저에 폴더가 연결되지 않았어도 서버에 등록된 내 환경(앱에서 만든 것)을 읽기 전용으로 쓴다.
   * 웹 브라우저에서 검색할 때 게임 버전을 알기 위한 값이다. 설치·업데이트에는 쓰지 않는다.
   */
  serverEnv: Environment | null
  /** 폴더를 바꾸면 늘어난다. 핸들을 다시 읽어야 하는 곳에서 의존성으로 쓴다. */
  folderRev: number
  /** 폴더를 연결한다. 이미 찾은 폴더(데스크톱 앱의 자동 검색 결과)를 주면 선택 창 없이 그걸 쓴다. */
  connect: (preset?: FileSystemDirectoryHandle) => Promise<void>
  changeFolder: () => Promise<void>
  grantAccess: () => Promise<void>
}

const EnvContext = createContext<EnvState | null>(null)

interface Linked {
  env: Environment
  granted: boolean
}

const isAbort = (e: unknown) => (e as DOMException)?.name === 'AbortError'

/** 폴더 선택 창 → 실제 AddOns 폴더 해석. 사용자가 취소하면 null. */
async function pickResolved(): Promise<FileSystemDirectoryHandle | null> {
  try {
    return await resolveAddonsFolder(await pickAddonsFolder())
  } catch (e) {
    if (isAbort(e)) return null
    throw e
  }
}

export function EnvProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['environments'], queryFn: api.environments })
  // undefined: 확인 중, null: 연결된 환경 없음
  const [linked, setLinked] = useState<Linked | null | undefined>(undefined)
  const [folderRev, setFolderRev] = useState(0)

  // 서버의 환경 중 이 브라우저에 폴더 핸들이 있는 첫 번째를 현재 환경으로 쓴다
  useEffect(() => {
    if (!data) return
    let cancelled = false
    ;(async () => {
      for (const env of data) {
        const handle = await loadHandle(env.id).catch(() => undefined)
        if (handle) {
          const granted = await hasPermission(handle).catch(() => false)
          if (!cancelled) setLinked({ env, granted })
          return
        }
      }
      if (!cancelled) setLinked(null)
    })()
    return () => {
      cancelled = true
    }
  }, [data])

  const connect = useCallback(async (preset?: FileSystemDirectoryHandle) => {
    const addons = preset ?? (await pickResolved())
    if (!addons) return
    const gameVersion = await detectGameVersion(addons)
    const count = data?.length ?? 0
    const env = await api.createEnvironment(count === 0 ? '내 PC' : `내 PC (${count + 1})`, gameVersion)
    await saveHandle(env.id, addons)
    await qc.invalidateQueries({ queryKey: ['environments'] })
    setLinked({ env, granted: true })
    setFolderRev((n) => n + 1)
  }, [data, qc])

  const changeFolder = useCallback(async () => {
    if (!linked) return
    const addons = await pickResolved()
    if (!addons) return
    const gameVersion = await detectGameVersion(addons)
    await saveHandle(linked.env.id, addons)
    if (gameVersion !== linked.env.gameVersion) {
      await api.updateEnvironment(linked.env.id, linked.env.name, gameVersion)
    }
    // 폴더가 바뀌었으니 이전 스캔 결과는 버린다
    qc.setQueryData(['scan', linked.env.id], null)
    await qc.invalidateQueries({ queryKey: ['environments'] })
    setLinked({ env: { ...linked.env, gameVersion }, granted: true })
    setFolderRev((n) => n + 1)
  }, [linked, qc])

  const grantAccess = useCallback(async () => {
    if (!linked) return
    const handle = await loadHandle(linked.env.id)
    if (!handle) {
      setLinked(null)
      return
    }
    // 권한 창은 사용자 클릭 안에서만 뜬다
    if (await ensurePermission(handle)) setLinked({ ...linked, granted: true })
  }, [linked])

  const status: EnvStatus =
    isLoading || linked === undefined ? 'loading' : linked === null ? 'needsFolder' : linked.granted ? 'ready' : 'needsPermission'

  const value = useMemo<EnvState>(
    () => ({
      status, current: linked?.env ?? null, serverEnv: data?.[0] ?? null, folderRev, connect, changeFolder, grantAccess,
    }),
    [status, linked, data, folderRev, connect, changeFolder, grantAccess],
  )
  return <EnvContext.Provider value={value}>{children}</EnvContext.Provider>
}

export function useEnv(): EnvState {
  const ctx = useContext(EnvContext)
  if (!ctx) throw new Error('EnvProvider 안에서 사용해야 합니다.')
  return ctx
}
