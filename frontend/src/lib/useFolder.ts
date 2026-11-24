import { useCallback, useEffect, useState } from 'react'
import { useEnv } from './EnvContext'
import { ensurePermission, loadHandle } from './handles'

/** 환경에 연결된 AddOns 폴더 핸들을 읽는다. 폴더 선택·교체는 EnvContext 가 담당한다. */
export function useFolder(envId: number | null) {
  const { folderRev } = useEnv()
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
  }, [envId, folderRev])

  /** 읽기/쓰기 권한이 확인된 핸들을 돌려준다. 클릭 이벤트 안에서 호출해야 권한 창이 뜬다. */
  const requireAccess = useCallback(async () => {
    if (!handle) throw new Error('AddOns 폴더가 연결되지 않았습니다.')
    if (!(await ensurePermission(handle))) throw new Error('폴더 접근 권한이 필요합니다.')
    return handle
  }, [handle])

  return { handle, loading, requireAccess }
}
