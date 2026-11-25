import { useEffect } from 'react'
import { api } from './api'
import type { LibraryAddon, LocalFolder } from './types'

const key = (envId: number) => `addon-manager:last-snapshot:${envId}`

/**
 * 폴더를 읽을 때마다 "내 애드온 목록"을 서버 보관함에 자동으로 남긴다.
 * 와우를 새로 깔거나 폴더를 다 지워도 예전 목록으로 그대로 다시 설치할 수 있게 하려는 것이다.
 *
 * - 자료실에 있는 애드온만 담는다(자료실이 아닌 폴더는 다시 설치할 방법이 없다).
 * - 목록이 비어 있으면 보내지 않는다. 실수로 다 지운 직후의 "없음"이 좋은 기록을 밀어내지 않게 하려는 것이다.
 * - 직전에 보낸 목록과 같으면 보내지 않는다(서버도 한 번 더 걸러 낸다). 서버에 알리지 못해도 화면에는 영향이 없다.
 */
export function useAutoSnapshot(envId: number | null, local: LocalFolder[] | undefined, library: LibraryAddon[] | undefined) {
  useEffect(() => {
    if (envId == null || !local || !library) return
    const inLibrary = new Map(library.map((a) => [a.name.toLowerCase(), a.name]))
    const names = local.map((l) => inLibrary.get(l.name.toLowerCase())).filter((n): n is string => !!n)
    if (names.length === 0) return
    const signature = [...names].sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase())).join(',')
    try {
      if (localStorage.getItem(key(envId)) === signature) return
    } catch {
      /* 저장소를 쓸 수 없으면 매번 보낸다. 서버가 같은 목록이면 남기지 않는다. */
    }
    api.autoSnapshot(envId, names).then(() => {
      try {
        localStorage.setItem(key(envId), signature)
      } catch {
        /* 무시 */
      }
    }).catch(() => undefined)
  }, [envId, local, library])
}
