import { useState } from 'react'
import { Navigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useAuth } from '../lib/AuthContext'
import type { AccountStatus } from '../lib/types'

const LABEL: Record<AccountStatus, string> = { PENDING: '승인 대기', ACTIVE: '활성', REJECTED: '거절' }

export default function AdminPage() {
  const { isAdmin } = useAuth()
  const qc = useQueryClient()
  const [status, setStatus] = useState<AccountStatus>('PENDING')

  // 화면 접근 제한은 편의일 뿐이고, 실제 권한 검사는 서버(@PreAuthorize)가 한다
  const { data, isLoading, error } = useQuery({
    queryKey: ['admin-accounts', status],
    queryFn: () => api.adminAccounts(status),
    enabled: isAdmin,
  })

  const act = useMutation({
    mutationFn: ({ id, approve }: { id: number; approve: boolean }) =>
      approve ? api.approveAccount(id) : api.rejectAccount(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-accounts'] }),
  })

  if (!isAdmin) return <Navigate to="/" replace />

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <h1 className="text-xl font-bold">계정 승인</h1>
        <div className="ml-auto flex gap-1">
          {(Object.keys(LABEL) as AccountStatus[]).map((s) => (
            <button key={s} className={`btn ${status === s ? 'btn-primary' : ''}`} onClick={() => setStatus(s)}>
              {LABEL[s]}
            </button>
          ))}
        </div>
      </div>

      {isLoading && <p className="text-slate-400">불러오는 중...</p>}
      {error && <p className="text-sm text-rose-400">{(error as Error).message}</p>}
      {act.error && <p className="text-sm text-rose-400">{(act.error as Error).message}</p>}

      <ul className="space-y-2">
        {data?.map((a) => (
          <li key={a.id} className="card flex flex-wrap items-center gap-3">
            <div className="flex-1">
              <div className="font-medium">{a.name} <span className="text-sm text-slate-400">({a.loginId})</span></div>
              <div className="text-xs text-slate-400">{a.role} · 가입 {a.createdAt.replace('T', ' ').slice(0, 16)}</div>
            </div>
            {a.role !== 'ADMIN' && (
              <div className="flex gap-2">
                {a.status !== 'ACTIVE' && (
                  <button className="btn btn-primary" disabled={act.isPending} onClick={() => act.mutate({ id: a.id, approve: true })}>승인</button>
                )}
                {a.status !== 'REJECTED' && (
                  <button className="btn btn-danger" disabled={act.isPending} onClick={() => act.mutate({ id: a.id, approve: false })}>
                    {a.status === 'ACTIVE' ? '비활성화' : '거절'}
                  </button>
                )}
              </div>
            )}
          </li>
        ))}
        {data?.length === 0 && <li className="text-slate-400">해당 상태의 계정이 없습니다.</li>}
      </ul>
    </div>
  )
}
