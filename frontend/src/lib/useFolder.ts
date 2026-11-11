import { useCallback, useEffect, useState } from 'react'
import { deleteHandle, ensurePermission, loadHandle, pickAddonsFolder, saveHandle } from './handles'

/** 환경에 연결된 AddOns 폴더 핸들을 다룬다. */
export function useFolder(envId: number | null) {
  const [handle, setHandle] = useState<FileSystemDirectoryHandle | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setHandle(null)
    if (envId == null) {
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    loadHandle(envId)
      .then((h) => !cancelled && setHandle(h ?? null))
      .catch(() => undefined)
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [envId])

  /** 폴더 선택 창을 연다. 사용자가 취소하면 null. */
  const pick = useCallback(async () => {
    if (envId == null) return null
    try {
      const h = await pickAddonsFolder()
      await saveHandle(envId, h)
      setHandle(h)
      return h
    } catch (e) {
      if ((e as DOMException).name === 'AbortError') return null
      throw e
    }
  }, [envId])

  const unlink = useCallback(async () => {
    if (envId == null) return
    await deleteHandle(envId)
    setHandle(null)
  }, [envId])

  /** 읽기/쓰기 권한이 확인된 핸들을 돌려준다. 클릭 이벤트 안에서 호출해야 권한 창이 뜬다. */
  const requireAccess = useCallback(async () => {
    if (!handle) throw new Error('AddOns 폴더가 연결되지 않았습니다.')
    if (!(await ensurePermission(handle))) throw new Error('폴더 접근 권한이 필요합니다.')
    return handle
  }, [handle])

  return { handle, loading, pick, unlink, requireAccess }
}
