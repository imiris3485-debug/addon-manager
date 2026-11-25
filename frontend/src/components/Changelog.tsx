import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'

/**
 * 파일의 변경 내역. 보여 달라고 할 때만 불러온다(목록의 파일마다 미리 부르면 CurseForge 호출이 너무 많다).
 * 서버가 HTML 을 지운 글자를 주므로 텍스트로만 그린다(dangerouslySetInnerHTML 을 쓰지 않는다).
 */
export function Changelog({ projectId, fileId }: { projectId: number; fileId: number }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['changelog', projectId, fileId],
    queryFn: () => api.changelog(projectId, fileId),
    staleTime: 10 * 60 * 1000,
  })

  if (isLoading) return <p className="text-xs text-slate-400">변경 내역을 불러오는 중...</p>
  if (error) return <p className="text-xs text-rose-400">변경 내역을 불러오지 못했습니다.</p>
  if (!data?.text) return <p className="text-xs text-slate-500">작성자가 변경 내역을 남기지 않았습니다.</p>
  return (
    <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded-md bg-slate-900/70 p-3 font-sans text-xs text-slate-300">
      {data.text}
    </pre>
  )
}
