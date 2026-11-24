import { useState } from 'react'
import { Navigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useAuth } from '../lib/AuthContext'
import { INSTALLER_NAME, MANIFEST_NAME, pickReleaseFiles, uploadRelease } from '../lib/releaseUpload'

const mb = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)}MB`

export default function AdminReleasePage() {
  const { isAdmin } = useAuth()
  const qc = useQueryClient()
  const { data: current, isLoading } = useQuery({ queryKey: ['app-release'], queryFn: api.adminRelease, enabled: isAdmin })

  const [files, setFiles] = useState<File[]>([])
  const [progress, setProgress] = useState<{ sent: number; total: number } | null>(null)
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)

  // 메뉴를 숨기는 것은 화면 표시일 뿐이다. 실제 권한 검사는 서버(@PreAuthorize)가 한다.
  if (!isAdmin) return <Navigate to="/" replace />

  const { installer, manifest, problem } = pickReleaseFiles(files)
  const busy = progress !== null

  const upload = async () => {
    if (!installer || !manifest) return
    setMessage(null)
    setProgress({ sent: 0, total: installer.size })
    try {
      const result = await uploadRelease(installer, manifest, (sent, total) => setProgress({ sent, total }))
      setMessage({ kind: 'ok', text: `버전 ${result.version} 을(를) 게시했습니다. 실행 중인 앱이 곧 업데이트를 받습니다.` })
      setFiles([])
      await qc.invalidateQueries({ queryKey: ['app-release'] })
    } catch (e) {
      setMessage({ kind: 'error', text: (e as Error).message })
    } finally {
      setProgress(null)
    }
  }

  return (
    <div className="mx-auto max-w-2xl space-y-4">
      <div>
        <h1 className="text-xl font-bold">앱 배포</h1>
        <p className="text-sm text-slate-400">
          데스크톱 앱의 새 빌드를 올립니다. 올리면 웹의 다운로드 화면이 바뀌고, 실행 중인 앱은 새 버전을 확인해 자동으로 업데이트합니다.
        </p>
      </div>

      <div className="card text-sm">
        {isLoading ? '확인 중...' : current?.published
          ? <>현재 게시된 버전 <b>{current.version}</b> · {mb(current.size)}
              {current.releaseDate && <> · {new Date(current.releaseDate).toLocaleString()}</>}</>
          : '아직 게시된 앱이 없습니다.'}
      </div>

      <div className="card space-y-3">
        <p className="text-sm text-slate-300">
          빌드 결과 폴더(<code>desktop/release</code>)에서 <b>{INSTALLER_NAME}</b> 와 <b>{MANIFEST_NAME}</b> 두 파일을 함께 선택하세요.
        </p>
        <input
          type="file" multiple accept=".exe,.yml" disabled={busy}
          className="input"
          onChange={(e) => {
            setMessage(null)
            setFiles(Array.from(e.target.files ?? []))
          }}
        />
        {files.length > 0 && problem && <p className="text-sm text-amber-300">{problem}</p>}
        {installer && manifest && (
          <p className="text-sm text-slate-400">{installer.name} ({mb(installer.size)}) · {manifest.name}</p>
        )}

        {progress && (
          <div className="space-y-1">
            <div className="h-2 overflow-hidden rounded bg-slate-800">
              <div className="h-full bg-indigo-500 transition-all" style={{ width: `${(progress.sent / progress.total) * 100}%` }} />
            </div>
            <p className="text-xs text-slate-400">{mb(progress.sent)} / {mb(progress.total)} 올리는 중... 창을 닫지 마세요.</p>
          </div>
        )}

        <button className="btn btn-primary" disabled={busy || !installer || !manifest} onClick={upload}>
          {busy ? '올리는 중...' : '올리고 게시'}
        </button>
        {message && (
          <p className={`text-sm ${message.kind === 'ok' ? 'text-emerald-400' : 'text-rose-400'}`}>{message.text}</p>
        )}
      </div>

      <p className="text-xs text-slate-500">
        버전은 빌드할 때 커밋 수로 자동 결정됩니다. 빌드 전에 변경을 커밋해야 새 버전 번호가 붙어 앱이 업데이트를 받습니다.
        이미 게시된 버전보다 낮은 버전은 올릴 수 없습니다.
      </p>
    </div>
  )
}
