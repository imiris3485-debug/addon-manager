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

/**
 * 폴더 `Name` 의 .toc 파일인지 판별한다. `Name.toc` 뿐 아니라 클라이언트별로 나뉜
 * `Name_Vanilla.toc`, `Name-Classic.toc` 처럼 폴더 이름 뒤에 구분자(_ 또는 -)가 오는 것도 포함한다.
 * (Questie 처럼 `Name.toc` 가 "지원하지 않는 클라이언트" 안내용 껍데기이고 실제 정보가 다른 .toc 에 있는 애드온이 많다)
 */
export function isTocFileFor(folderName: string, fileName: string): boolean {
  const file = fileName.toLowerCase()
  const name = folderName.toLowerCase()
  if (!file.endsWith('.toc') || !file.startsWith(name)) return false
  const rest = file.slice(name.length, -'.toc'.length)
  return rest === '' || rest.startsWith('_') || rest.startsWith('-')
}

/**
 * 한 폴더의 여러 .toc 에서 버전과 프로젝트 ID 를 모은다. 파일 이름순으로 처음 나온 값을 쓴다.
 * ⚠️ 스캔(scanner)과 설치(installer)가 같은 규칙을 써야 한다. 설치할 때 기록한 버전과 스캔한 버전이
 *    다르면 서버가 기록한 설치 파일을 믿지 않고 "알 수 없음"으로 판단한다.
 */
export function mergeTocs(tocs: { fileName: string; text: string }[]): { version: string | null; projectId: number | null } {
  const sorted = [...tocs].sort((a, b) => a.fileName.toLowerCase().localeCompare(b.fileName.toLowerCase()))
  let version: string | null = null
  let projectId: number | null = null
  for (const t of sorted) {
    const info = parseToc(t.text)
    if (version == null && info.version) version = info.version
    if (projectId == null && info.projectId != null) projectId = info.projectId
  }
  return { version, projectId }
}

/** 한 프로젝트의 여러 폴더 중, 정렬했을 때 처음으로 버전이 있는 값. 서버의 firstTocVersion 과 같은 규칙이다. */
export function firstVersion(folders: { folderName: string; tocVersion: string | null }[]): string | null {
  const sorted = [...folders].sort((a, b) => a.folderName.toLowerCase().localeCompare(b.folderName.toLowerCase()))
  return sorted.find((f) => f.tocVersion && f.tocVersion.trim())?.tocVersion ?? null
}
