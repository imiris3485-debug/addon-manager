import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { hashFolder } from './librarySync'
import { loadInstalledHashes } from './library'
import type { LibraryAddon, LocalFolder } from './types'

/**
 * 이 PC 폴더의 실제 내용 해시(자료실에 있는 애드온만). 어떻게 설치했든(커스포지, 직접 복사 등) 자료실과 내용이 같은지 정확히 안다.
 * 버전 문자열만 비교하면 "버전은 같은데 내용이 다른" 경우를 놓친다. 모든 폴더가 아니라 자료실 항목과 이름이 같은 것만 계산해 부담을 줄인다.
 *
 * @param stamp 폴더를 다시 읽을 때마다 바뀌는 값(설치·삭제 뒤 내용이 바뀌면 다시 계산하려는 용도)
 * @returns 폴더 이름(소문자) → 내용 해시. 설치 기록과 합쳐 libraryState 에 그대로 넘기면 된다(실제 해시가 우선).
 */
export function useInstalledHashes(
  envId: number | null,
  root: FileSystemDirectoryHandle | null | undefined,
  local: LocalFolder[] | undefined,
  library: LibraryAddon[] | undefined,
  stamp: number,
): { hashes: Record<string, string>; hashing: boolean } {
  const { data, isFetching } = useQuery({
    queryKey: ['installed-hashes', envId, stamp],
    enabled: envId != null && !!root && !!local && !!library,
    staleTime: 0,
    queryFn: async () => {
      const inLibrary = new Set(library!.map((a) => a.name.toLowerCase()))
      const actual: Record<string, string> = {}
      for (const l of local!) {
        if (!inLibrary.has(l.name.toLowerCase())) continue
        try {
          actual[l.name.toLowerCase()] = await hashFolder(root!, l.name)
        } catch {
          /* 읽지 못한 폴더는 설치 기록·버전으로 판단한다 */
        }
      }
      return actual
    },
  })
  // 실제 해시가 있으면 그것을, 없으면 이 앱으로 설치할 때 남긴 기록을 쓴다
  // 폴더를 다시 읽을 때(stamp)와 계산 결과가 바뀔 때만 새로 만든다(화면의 useMemo 들이 매번 다시 돌지 않게)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const hashes = useMemo(() => ({ ...(envId != null ? loadInstalledHashes(envId) : {}), ...(data ?? {}) }), [envId, data, stamp])
  return { hashes, hashing: isFetching }
}
