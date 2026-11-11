import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useEnv } from '../lib/EnvContext'
import { deleteHandle } from '../lib/handles'
import type { Environment } from '../lib/types'
import { useFolder } from '../lib/useFolder'

function EnvRow({ env }: { env: Environment }) {
  const qc = useQueryClient()
  const folder = useFolder(env.id)
  const [error, setError] = useState<string | null>(null)

  const remove = useMutation({
    mutationFn: async () => {
      await api.deleteEnvironment(env.id)
      await deleteHandle(env.id)
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['environments'] }),
    onError: (e) => setError((e as Error).message),
  })

  const pick = async () => {
    setError(null)
    try {
      const h = await folder.pick()
      if (h && h.name.toLowerCase() !== 'addons') {
        // 잘못된 폴더(예: WoW 루트)를 고르면 다른 폴더를 건드릴 수 있어 이름으로 한 번 더 알려 준다
        alert(`선택한 폴더 이름이 '${h.name}' 입니다. 보통 Interface\\AddOns 폴더를 선택합니다.`)
      }
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <li className="card flex flex-wrap items-center gap-3">
      <div className="min-w-0 flex-1">
        <div className="font-medium">{env.name} <span className="text-sm text-slate-400">· 게임 버전 {env.gameVersion}</span></div>
        <div className="text-xs text-slate-400">
          {folder.loading ? '확인 중...' : folder.handle ? `연결된 폴더: ${folder.handle.name}` : '이 브라우저에는 폴더가 연결되지 않았습니다'}
        </div>
        {error && <div className="text-xs text-rose-400">{error}</div>}
      </div>
      <button className="btn" onClick={pick}>{folder.handle ? '폴더 변경' : 'AddOns 폴더 연결'}</button>
      <button className="btn btn-danger" disabled={remove.isPending}
              onClick={() => confirm(`'${env.name}' 환경과 설치 기록을 삭제할까요? (실제 폴더는 그대로 둡니다)`) && remove.mutate()}>
        삭제
      </button>
    </li>
  )
}

export default function EnvironmentsPage() {
  const qc = useQueryClient()
  const { environments, loading } = useEnv()
  const [name, setName] = useState('')
  const [gameVersion, setGameVersion] = useState('')
  const [error, setError] = useState<string | null>(null)
  const { data: versionGroups } = useQuery({
    queryKey: ['game-versions'], queryFn: api.gameVersions, staleTime: 60 * 60 * 1000,
  })

  const create = useMutation({
    mutationFn: () => api.createEnvironment(name.trim(), gameVersion.trim()),
    onSuccess: () => {
      setName('')
      setGameVersion('')
      setError(null)
      qc.invalidateQueries({ queryKey: ['environments'] })
    },
    onError: (e) => setError((e as Error).message),
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate()
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold">게임 환경</h1>
        <p className="text-sm text-slate-400">
          게임 설치(클라이언트)마다 환경을 만들고 AddOns 폴더를 연결합니다. 게임 버전은 CurseForge 파일 필터에 사용됩니다.
        </p>
      </div>

      <form onSubmit={submit} className="card flex flex-wrap items-end gap-3">
        <label className="flex-1 space-y-1 text-sm">
          <span className="text-slate-400">이름</span>
          <input className="input" placeholder="예: 메인 PC" value={name} onChange={(e) => setName(e.target.value)} required maxLength={50} />
        </label>
        <label className="w-56 space-y-1 text-sm">
          <span className="text-slate-400">게임 버전</span>
          {versionGroups && versionGroups.length > 0 ? (
            <select className="input" value={gameVersion} onChange={(e) => setGameVersion(e.target.value)} required>
              <option value="">버전 선택</option>
              {versionGroups.map((g) => (
                <optgroup key={g.typeName} label={g.typeName}>
                  {g.versions.map((v) => <option key={`${g.typeName}-${v}`} value={v}>{v}</option>)}
                </optgroup>
              ))}
            </select>
          ) : (
            // 목록을 불러오지 못하면(API 키 미설정 등) 직접 입력할 수 있게 한다
            <input className="input" placeholder="예: 3.3.5" value={gameVersion}
                   onChange={(e) => setGameVersion(e.target.value)} required maxLength={20} />
          )}
        </label>
        <button className="btn btn-primary" disabled={create.isPending}>환경 추가</button>
        {error && <p className="w-full text-sm text-rose-400">{error}</p>}
      </form>

      {loading ? <p className="text-slate-400">불러오는 중...</p> : (
        <ul className="space-y-2">
          {environments.map((env) => <EnvRow key={env.id} env={env} />)}
          {environments.length === 0 && <li className="text-slate-400">등록된 환경이 없습니다.</li>}
        </ul>
      )}
    </div>
  )
}
