export interface TocInfo {
  version: string | null
  projectId: number | null
  /** `## Interface: 30300` 같은 클라이언트 빌드 번호. 게임 버전 자동 감지에 쓴다. */
  interfaceVersion: number | null
  /** .toc 가 나열한 파일 경로(폴더 기준 상대 경로, 구분자는 /) */
  files: string[]
}

/** .toc 텍스트를 파싱한다. `## Key: Value` 는 메타데이터, `#` 주석 외의 줄은 로드할 파일이다. */
export function parseToc(text: string): TocInfo {
  const info: TocInfo = { version: null, projectId: null, interfaceVersion: null, files: [] }
  for (const raw of text.replace(/^﻿/, '').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line) continue
    if (line.startsWith('##')) {
      const m = /^##\s*([^:]+?)\s*:\s*(.*)$/.exec(line)
      if (!m) continue
      const key = m[1].toLowerCase()
      if (key === 'version') info.version = m[2].trim() || null
      if (key === 'interface') {
        // 쉼표로 여러 값이 올 수 있어(예: 11500, 30403) 첫 값만 쓴다
        const n = Number(m[2].split(',')[0].trim())
        if (Number.isInteger(n) && n > 0) info.interfaceVersion = n
      }
      if (key === 'x-curse-project-id') {
        const id = Number(m[2].trim())
        if (Number.isInteger(id) && id > 0) info.projectId = id
      }
      continue
    }
    if (line.startsWith('#')) continue
    info.files.push(line.replace(/\\/g, '/'))
  }
  return info
}

/** 한 프로젝트의 여러 폴더 중, 정렬했을 때 처음으로 버전이 있는 값. 서버의 firstTocVersion 과 같은 규칙이다. */
export function firstVersion(folders: { folderName: string; tocVersion: string | null }[]): string | null {
  const sorted = [...folders].sort((a, b) => a.folderName.toLowerCase().localeCompare(b.folderName.toLowerCase()))
  return sorted.find((f) => f.tocVersion && f.tocVersion.trim())?.tocVersion ?? null
}
