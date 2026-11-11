// 토큰은 localStorage 에 둔다(개인 도구 용도). 만료/무효 시 서버가 401 을 주면 api.ts 가 지운다.
const TOKEN_KEY = 'access-token'

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
  window.dispatchEvent(new Event('auth-changed'))
}

export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* 저장소를 쓸 수 없으면 무시 */
  }
  window.dispatchEvent(new Event('auth-changed'))
}

export interface TokenInfo {
  loginId: string
  roles: string[]
  expiresAt: number
}

/**
 * 화면 표시용으로만 토큰을 읽는다(관리자 메뉴 노출 등).
 * 서명 검증은 서버가 하며, 여기서 읽은 값으로 권한을 판단하지 않는다.
 */
export function readToken(token: string | null): TokenInfo | null {
  if (!token) return null
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const json = JSON.parse(decodeURIComponent(escape(atob(payload))))
    const info: TokenInfo = {
      loginId: json.sub,
      roles: String(json.roles ?? '').split(',').filter(Boolean),
      expiresAt: Number(json.exp) * 1000,
    }
    return info.expiresAt > Date.now() ? info : null
  } catch {
    return null
  }
}
