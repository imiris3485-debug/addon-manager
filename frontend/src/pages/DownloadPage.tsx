import { useEffect, useState } from 'react'
import { isDesktop } from '../lib/nativeFs'

// 설치 파일과 업데이트 정보(latest.yml)는 서버의 /downloads 에서 내려준다. 관리자가 "앱 배포"에서 올린다.
const INSTALLER_URL = '/downloads/AddonManager-Setup.exe'
const MANIFEST_URL = '/downloads/latest.yml'

type Availability = 'checking' | 'ready' | 'missing'

export default function DownloadPage() {
  const [availability, setAvailability] = useState<Availability>('checking')
  const [version, setVersion] = useState<string | null>(null)

  // 아직 올라오지 않았을 때 깨진 링크 대신 안내를 보여 주려고 작은 정보 파일로 미리 확인한다
  useEffect(() => {
    let cancelled = false
    fetch(MANIFEST_URL, { cache: 'no-cache' })
      .then(async (res) => {
        if (!res.ok) throw new Error('missing')
        const found = /^version:\s*(\S+)/m.exec(await res.text())
        if (!cancelled) {
          setVersion(found ? found[1] : null)
          setAvailability('ready')
        }
      })
      .catch(() => !cancelled && setAvailability('missing'))
    return () => {
      cancelled = true
    }
  }, [])

  if (isDesktop()) {
    return <div className="card text-center text-slate-300">이미 데스크톱 앱에서 사용 중입니다.</div>
  }

  return (
    <div className="mx-auto max-w-2xl space-y-4">
      <div>
        <h1 className="text-xl font-bold">데스크톱 앱</h1>
        <p className="text-sm text-slate-400">
          웹 브라우저는 보안 때문에 PC 의 와우 폴더(특히 Program Files 안)를 직접 다룰 수 없습니다.
          설치형 앱을 쓰면 와우 폴더를 자동으로 찾아서 애드온을 설치·업데이트·삭제합니다.
        </p>
      </div>

      <div className="card space-y-3">
        {availability === 'ready' ? (
          <a className="btn btn-primary" href={INSTALLER_URL} download>
            Windows 설치 파일 받기{version ? ` (v${version})` : ''}
          </a>
        ) : (
          <button className="btn" disabled>
            {availability === 'checking' ? '확인 중...' : '설치 파일 준비 중'}
          </button>
        )}
        {availability === 'missing' && (
          <p className="text-sm text-amber-300">설치 파일이 아직 올라오지 않았습니다. 관리자에게 문의하세요.</p>
        )}
      </div>

      <ol className="card list-decimal space-y-2 pl-8 text-sm text-slate-300">
        <li>설치 파일을 받아 실행합니다. 관리자 권한은 필요 없습니다.</li>
        <li>
          Windows 가 "PC 를 보호했습니다" 경고를 띄우면 <b>추가 정보 → 실행</b> 을 누릅니다.
          (코드 서명이 없는 앱이라 처음에 나오는 경고입니다.)
        </li>
        <li>앱이 열리면 이 사이트와 같은 계정으로 로그인합니다.</li>
        <li>와우 폴더가 자동으로 연결되고, 설치된 애드온과 업데이트 상태가 바로 표시됩니다.</li>
        <li>이후 앱의 새 버전은 실행 중에 자동으로 확인해 업데이트합니다. 다시 받을 필요가 없습니다.</li>
      </ol>
    </div>
  )
}
