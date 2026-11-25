// 폴더 내용 해시. 서버의 AddonZipInspector(contentHash)와 같은 규칙이다. 한쪽만 바꾸면 "내용이 같은데 다르다"고 판단하므로
// 규칙을 바꿀 때는 서버와 함께 바꾼다.
//
//   해시 = SHA-256( 경로순으로 "경로\0파일SHA-256(16진수)\n" 을 이은 문자열(UTF-8) )
//
// 경로는 "폴더/하위/파일" 형태(구분자 /), 정렬은 UTF-16 코드 유닛 순(JS 기본 정렬, Java String.compareTo 와 같다).
// 디렉터리 항목은 넣지 않는다. 압축 방식이나 항목 순서와 무관하게 내용이 같으면 같은 값이 나온다.

const encoder = new TextEncoder()

export async function sha256Hex(data: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', data as unknown as BufferSource)
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}

/** 이미 계산한 [경로, 파일 해시] 목록을 한 값으로 합친다. */
export async function combineFileHashes(pairs: [string, string][]): Promise<string> {
  const sorted = [...pairs].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
  let lines = ''
  for (const [path, hex] of sorted) lines += `${path}\0${hex}\n`
  return sha256Hex(encoder.encode(lines))
}

/** 파일 내용이 메모리에 있을 때(zip 을 푼 결과)의 내용 해시. */
export async function contentHash(files: Map<string, Uint8Array>): Promise<string> {
  const pairs: [string, string][] = []
  for (const [path, data] of files) pairs.push([path, await sha256Hex(data)])
  return combineFileHashes(pairs)
}
