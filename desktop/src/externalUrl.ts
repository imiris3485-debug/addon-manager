/**
 * 앱 밖(기본 브라우저)으로 열어도 되는 주소를 가려낸다.
 *
 * 웹 화면은 믿지 않는다. 화면이 임의의 주소(file:, 프로그램 실행용 사용자 지정 프로토콜 등)를 열게 하면
 * PC 에서 의도하지 않은 프로그램이 실행될 수 있으므로 https 주소만 허용한다.
 */
export function isAllowedExternalUrl(raw: string): boolean {
  try {
    const url = new URL(raw)
    return url.protocol === 'https:' && url.hostname.length > 0
  } catch {
    return false
  }
}
