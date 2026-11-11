import { Link, Navigate } from 'react-router-dom'
import { useAuth } from '../lib/AuthContext'

const FEATURES = [
  { title: '애드온 검색', body: 'CurseForge 에 등록된 World of Warcraft 애드온을 게임 버전에 맞게 검색합니다.' },
  { title: '설치 현황 확인', body: '내 AddOns 폴더를 스캔해 어떤 애드온이 설치되어 있는지 한눈에 보여 줍니다.' },
  { title: '업데이트 관리', body: '설치된 파일과 최신 파일을 비교해 업데이트 여부를 알려 주고, 한 번에 업데이트합니다.' },
  { title: '여러 게임 환경', body: '클라이언트 버전별로 환경을 나누어 계정마다 애드온을 따로 관리합니다.' },
]

const STEPS = [
  '계정을 만들고 관리자 승인을 받습니다.',
  '게임 환경을 만들고 내 컴퓨터의 AddOns 폴더를 연결합니다.',
  '검색해서 설치하거나, 설치된 애드온의 업데이트를 확인합니다.',
]

/** 로그인 전에 보이는 소개 화면. 로그인한 사용자는 바로 설치된 애드온 화면으로 보낸다. */
export default function LandingPage() {
  const { info } = useAuth()
  if (info) return <Navigate to="/installed" replace />

  return (
    <div className="min-h-full">
      <header className="mx-auto flex max-w-5xl items-center gap-3 px-4 py-4">
        <span className="text-lg font-bold text-indigo-400">Addon Manager</span>
        <div className="ml-auto flex gap-2">
          <Link className="btn" to="/login">로그인</Link>
          <Link className="btn btn-primary" to="/signup">가입 요청</Link>
        </div>
      </header>

      <main className="mx-auto max-w-5xl space-y-12 px-4 py-10">
        <section className="space-y-4 text-center">
          <h1 className="text-3xl font-bold sm:text-4xl">WoW 애드온을 한곳에서 검색하고 관리하세요</h1>
          <p className="mx-auto max-w-2xl text-slate-300">
            Addon Manager 는 World of Warcraft 애드온을 검색·설치하고 업데이트 여부를 확인하는 웹 도구입니다.
            애드온 정보는 CurseForge 에서 가져오며, 설치와 업데이트는 내 브라우저가 내 컴퓨터의 AddOns 폴더에 직접 수행합니다.
          </p>
          <div className="flex justify-center gap-3">
            <Link className="btn btn-primary" to="/signup">시작하기</Link>
            <Link className="btn" to="/login">로그인</Link>
          </div>
        </section>

        <section>
          <h2 className="mb-4 text-xl font-bold">주요 기능</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            {FEATURES.map((f) => (
              <div key={f.title} className="card">
                <h3 className="mb-1 font-medium text-indigo-300">{f.title}</h3>
                <p className="text-sm text-slate-300">{f.body}</p>
              </div>
            ))}
          </div>
        </section>

        <section>
          <h2 className="mb-4 text-xl font-bold">사용 방법</h2>
          <ol className="space-y-2">
            {STEPS.map((s, i) => (
              <li key={s} className="card flex items-center gap-3">
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-indigo-600 text-sm font-bold">{i + 1}</span>
                <span className="text-sm text-slate-200">{s}</span>
              </li>
            ))}
          </ol>
        </section>

        <section className="card space-y-2 text-sm text-slate-300">
          <h2 className="text-base font-bold text-slate-100">데이터와 개인정보</h2>
          <ul className="list-disc space-y-1 pl-5">
            <li>CurseForge API 키는 서버에서만 사용하며 브라우저에 전달되지 않습니다.</li>
            <li>애드온 파일은 CurseForge 에서 받아 사용자 브라우저로 전달할 뿐, 서버에 저장하지 않습니다.</li>
            <li>서버에는 계정 정보와 환경별 설치 기록(애드온 ID, 파일 ID, 폴더명)만 저장합니다.</li>
            <li>컴퓨터의 폴더는 사용자가 직접 선택해 허용한 AddOns 폴더만 읽고 씁니다.</li>
            <li>가입은 관리자 승인 후 사용할 수 있습니다.</li>
          </ul>
        </section>
      </main>

      <footer className="border-t border-slate-800 px-4 py-6 text-center text-xs text-slate-500">
        <p>
          애드온 데이터 제공:{' '}
          <a className="text-indigo-400 hover:underline" href="https://www.curseforge.com/wow" target="_blank" rel="noreferrer">
            CurseForge
          </a>
        </p>
        <p className="mt-1">
          이 사이트는 개인이 운영하는 비공식 도구이며, CurseForge 및 Blizzard Entertainment 와 제휴하거나 보증을 받지 않았습니다.
          World of Warcraft 는 Blizzard Entertainment 의 상표입니다.
        </p>
        <p className="mt-1">폴더 설치 기능은 Chrome·Edge 등 Chromium 기반 브라우저에서 동작합니다.</p>
      </footer>
    </div>
  )
}
