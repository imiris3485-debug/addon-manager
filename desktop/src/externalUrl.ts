/**
 * 앱 밖(기본 브라우저·다른 프로그램)으로 열어도 되는 주소를 가려낸다.
 *
 * 웹 화면은 믿지 않는다. 화면이 임의의 주소(file:, 프로그램 실행용 사용자 지정 프로토콜 등)를 열게 하면
 * PC 에서 의도하지 않은 프로그램이 실행될 수 있으므로 아래 두 가지만 허용한다.
 *  1) https 주소(CurseForge 상세, 소스 코드 링크 등)
 *  2) CurseForge 앱으로 설치를 넘기는 주소: curseforge://install?addonId=숫자[&fileId=숫자]
 */
const NUMERIC = /^[0-9]{1,12}$/

export function isAllowedExternalUrl(raw: string): boolean {
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return false
  }
  if (url.protocol === 'https:') return url.hostname.length > 0

  if (url.protocol === 'curseforge:') {
    // curseforge://install?... 형태만. 경로나 계정 정보, 다른 매개변수가 붙은 것은 거부한다.
    if (url.hostname !== 'install' || (url.pathname !== '' && url.pathname !== '/') || url.username || url.password || url.hash) {
      return false
    }
    const keys = [...url.searchParams.keys()]
    if (keys.length === 0 || keys.some((k) => k !== 'addonId' && k !== 'fileId')) return false
    if (new Set(keys).size !== keys.length) return false             // 같은 매개변수를 여러 번 쓴 것도 거부
    const addonId = url.searchParams.get('addonId')
    const fileId = url.searchParams.get('fileId')
    return addonId !== null && NUMERIC.test(addonId) && (fileId === null || NUMERIC.test(fileId))
  }
  return false
}
