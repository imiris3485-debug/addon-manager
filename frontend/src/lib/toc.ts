export interface TocInfo {
  version: string | null
  /** `## Interface: 30300` 같은 클라이언트 빌드 번호. 게임 버전 자동 감지에 쓴다. */
  interfaceVersion: number | null
  /** `## Dependencies:` / `## RequiredDeps:` 가 요구하는 애드온 폴더(필수). OptionalDeps 는 받지 않는다. */
  requires: string[]
  /** .toc 가 나열한 파일 경로(폴더 기준 상대 경로, 구분자는 /) */
  files: string[]
}

/** 폴더 이름으로 쓸 수 있는 의존성 이름인지. 다른 폴더를 가리키므로 경로 문자가 섞이면 안 된다(서버 규칙과 같다). */
function isSafeFolderName(name: string): boolean {
  return name.length > 0 && name.length <= 100 && name !== '.' && name !== '..' && !/[<>:"|?*\\/,\u0000-\u001f]/.test(name)
}

/** .toc 텍스트를 파싱한다. `## Key: Value` 는 메타데이터, `#` 주석 외의 줄은 로드할 파일이다. */
export function parseToc(text: string): TocInfo {
  const info: TocInfo = { version: null, interfaceVersion: null, requires: [], files: [] }
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
      if (key === 'dependencies' || key === 'requireddeps') {
        for (const dep of m[2].split(',')) {
          const name = dep.trim()
          if (isSafeFolderName(name) && !info.requires.includes(name)) info.requires.push(name)
        }
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
 * 한 폴더의 여러 .toc 에서 버전과 필요한 애드온을 모은다. 버전은 파일 이름순으로 처음 나온 값,
 * 필요한 애드온은 모든 .toc 의 합집합이다(서버의 자료실 검사와 같은 규칙).
 */
export function mergeTocs(tocs: { fileName: string; text: string }[]): { version: string | null; requires: string[] } {
  const sorted = [...tocs].sort((a, b) => a.fileName.toLowerCase().localeCompare(b.fileName.toLowerCase()))
  let version: string | null = null
  const requires: string[] = []
  for (const t of sorted) {
    const info = parseToc(t.text)
    if (version == null && info.version) version = info.version
    for (const r of info.requires) if (!requires.includes(r)) requires.push(r)
  }
  return { version, requires }
}

/** 여러 폴더 중, 정렬했을 때 처음으로 버전이 있는 값. 서버가 자료실 항목에 기록하는 버전과 같은 규칙이다. */
export function firstVersion(folders: { folderName: string; tocVersion: string | null }[]): string | null {
  const sorted = [...folders].sort((a, b) => a.folderName.toLowerCase().localeCompare(b.folderName.toLowerCase()))
  return sorted.find((f) => f.tocVersion && f.tocVersion.trim())?.tocVersion ?? null
}
