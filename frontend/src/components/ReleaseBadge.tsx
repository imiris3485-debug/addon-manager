import type { ReleaseType } from '../lib/types'

const STYLE: Record<ReleaseType, { label: string; cls: string; title: string }> = {
  RELEASE: { label: '정식', cls: 'bg-emerald-500/15 text-emerald-300', title: '안정 버전' },
  BETA: { label: '베타', cls: 'bg-amber-500/15 text-amber-300', title: '시험 버전. 문제가 있을 수 있습니다' },
  ALPHA: { label: '알파', cls: 'bg-rose-500/15 text-rose-300', title: '개발 중인 버전. 불안정할 수 있습니다' },
}

export function ReleaseBadge({ type }: { type: ReleaseType }) {
  const s = STYLE[type] ?? STYLE.RELEASE
  return <span title={s.title} className={`rounded px-1.5 py-0.5 text-xs font-medium ${s.cls}`}>{s.label}</span>
}
