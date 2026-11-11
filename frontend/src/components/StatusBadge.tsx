import type { UpdateStatus } from '../lib/types'

const STYLE: Record<UpdateStatus, { label: string; cls: string }> = {
  UP_TO_DATE: { label: '최신', cls: 'bg-emerald-500/15 text-emerald-300' },
  UPDATE_AVAILABLE: { label: '업데이트 있음', cls: 'bg-amber-500/15 text-amber-300' },
  UNKNOWN: { label: '버전 확인 불가', cls: 'bg-slate-500/20 text-slate-300' },
  NO_FILE: { label: '해당 버전 파일 없음', cls: 'bg-rose-500/15 text-rose-300' },
}

export function StatusBadge({ status }: { status: UpdateStatus }) {
  const s = STYLE[status]
  return <span className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${s.cls}`}>{s.label}</span>
}
