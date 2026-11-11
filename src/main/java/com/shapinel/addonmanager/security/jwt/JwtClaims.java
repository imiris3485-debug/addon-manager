package com.shapinel.addonmanager.security.jwt;

import org.springframework.security.oauth2.jwt.Jwt;

public final class JwtClaims {

    private JwtClaims() {
    }

    /**
     * 토큰의 사용자 ID. 숫자 클레임은 파서에 따라 Integer/Long 으로 올 수 있어 Number 로 받는다.
     * 사용자 ID는 요청값이 아니라 검증된 토큰에서만 꺼낸다.
     */
    public static Long userId(Jwt jwt) {
        Number userId = jwt.getClaim("userId");
        return userId.longValue();
    }
}
