import * as fs from 'node:fs'
import * as path from 'node:path'

/**
 * 파일 작업의 안전 검사.
 * 화면(웹)에서 오는 경로는 믿지 않는다. 사이트가 해킹되거나 zip 에 악성 경로가 들어 있어도
 * 사용자가 연결한 AddOns 폴더 밖은 읽지도 쓰지도 지우지도 못하게 여기서 막는다.
 */

export type FsErrorCode = 'NotFound' | 'Invalid' | 'TooLarge' | 'Other'

export class FsError extends Error {
  readonly code: FsErrorCode

  constructor(code: FsErrorCode, message: string) {
    super(message)
    this.name = 'FsError'
    this.code = code
  }
}

// 경로는 문자열이 아니라 "이름 조각의 배열"로만 받는다. 구분자(/ \)를 해석할 일이 없어 우회 여지가 줄어든다.
// Windows 에서 쓸 수 없거나 다른 의미를 갖는 문자: 드라이브(C:)·대체 스트림(a:b)·와일드카드·제어문자
const FORBIDDEN_CHARS = /[<>:"/\\|?*\u0000-\u001f]/
// CON, NUL 같은 장치 이름은 확장자가 붙어도 파일이 아니라 장치로 열린다
const RESERVED_NAMES = /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$/i
const MAX_SEGMENT_LENGTH = 255
const MAX_DEPTH = 32

export function validateSegment(seg: unknown): string {
  if (typeof seg !== 'string' || seg.length === 0 || seg.length > MAX_SEGMENT_LENGTH) {
    throw new FsError('Invalid', '잘못된 경로입니다.')
  }
  if (seg === '.' || seg === '..' || FORBIDDEN_CHARS.test(seg) || RESERVED_NAMES.test(seg)) {
    throw new FsError('Invalid', `허용되지 않는 경로입니다: ${seg}`)
  }
  // Windows 는 이름 끝의 점/공백을 지워서 서로 다른 이름이 같은 파일이 된다
  if (/[. ]$/.test(seg)) {
    throw new FsError('Invalid', `허용되지 않는 경로입니다: ${seg}`)
  }
  return seg
}

export function validateSegments(segs: unknown): string[] {
  if (!Array.isArray(segs) || segs.length > MAX_DEPTH) {
    throw new FsError('Invalid', '잘못된 경로입니다.')
  }
  return segs.map(validateSegment)
}

/** 이어 붙인 결과가 기준 폴더 안인지 한 번 더 확인한다(조각 검증이 뚫려도 마지막으로 막는 안전망). */
export function resolveInside(rootReal: string, segs: string[]): string {
  const target = path.join(rootReal, ...segs)
  const rel = path.relative(rootReal, target)
  if (rel.startsWith('..') || path.isAbsolute(rel)) {
    throw new FsError('Invalid', '허용되지 않는 경로입니다.')
  }
  return target
}

/**
 * 경로 중간에 심볼릭 링크·정션이 있으면 거부한다.
 * 링크를 따라가면 AddOns 폴더 안에서 출발해도 폴더 밖(예: 문서, 시스템 폴더)을 건드릴 수 있다.
 * 이름 검사만으로는 막을 수 없어서 실제 파일 시스템을 확인한다.
 */
export async function assertNoLinks(rootReal: string, segs: string[]): Promise<void> {
  let current = rootReal
  for (const seg of segs) {
    current = path.join(current, seg)
    let stat: fs.Stats
    try {
      stat = await fs.promises.lstat(current)
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === 'ENOENT') return   // 아직 없는 경로는 새로 만들 곳이다
      throw e
    }
    if (stat.isSymbolicLink()) {
      throw new FsError('Invalid', '심볼릭 링크·정션 폴더는 사용할 수 없습니다.')
    }
  }
}
