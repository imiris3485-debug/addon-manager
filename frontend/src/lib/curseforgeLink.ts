/**
 * CurseForge 앱으로 설치를 넘기는 주소. CurseForge 웹사이트의 "앱으로 설치" 버튼이 쓰는 것과 같은 방식이다.
 * 작성자가 외부 배포를 막아 우리가 내려받을 수 없는 애드온을 대신 설치하는 용도다.
 *
 * ⚠️ 이 PC 에 CurseForge 앱이 설치돼 있어야 하고, 설치 위치는 CurseForge 앱의 설정을 따른다.
 *    우리 앱의 설치 기록에는 반영되지 않으므로 설치한 뒤 "설치된 애드온"에서 다시 스캔해야 한다.
 */
import { isDesktop } from './nativeFs'

/**
 * 링크에 펼쳐 쓰는 속성. 데스크톱 앱에서는 새 창 요청으로 보내야 앱(main)의 허용 규칙을 거쳐 OS 로 넘어간다.
 * 웹 브라우저는 그대로 두는 편이 낫다(새 탭이 빈 채로 남을 수 있다).
 */
export const curseforgeLinkAttrs = (): { target?: string; rel?: string } =>
  isDesktop() ? { target: '_blank', rel: 'noreferrer' } : {}

export function curseforgeInstallUrl(projectId: number, fileId?: number | null): string {
  const params = new URLSearchParams({ addonId: String(projectId) })
  if (fileId != null) params.set('fileId', String(fileId))
  return `curseforge://install?${params.toString()}`
}
