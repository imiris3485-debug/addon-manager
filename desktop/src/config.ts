import { app } from 'electron'

// 파일 기능을 쓸 수 있는 유일한 사이트. 이 출처에서 로드된 화면만 PC 파일 작업을 요청할 수 있다.
const PRODUCTION_URL = 'https://addon-manager.shapinel.com'
const DEV_URL = 'http://localhost:5173'

export function appUrl(): string {
  if (app.isPackaged) return PRODUCTION_URL
  // 개발 중에만 주소를 바꿀 수 있다. 배포본은 환경변수로 다른 사이트를 열 수 없게 고정한다.
  return process.env.ADDON_MANAGER_URL || DEV_URL
}

export function allowedOrigin(): string {
  return new URL(appUrl()).origin
}
