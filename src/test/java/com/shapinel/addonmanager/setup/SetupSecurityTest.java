package com.shapinel.addonmanager.setup;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세팅 공유·설치 기록 권한 정책
 * - 인증이 필요한 /api/ 경로에만 있고(SecurityConfig 의 /api/** 는 기본이 인증 필요) 공개 경로가 아니다.
 * - 관리자 전용이 아니므로 ADMIN 제한을 걸지 않는다(모든 승인된 사용자가 쓰는 기능). 본인 데이터만 다루는 것은
 *   서비스가 토큰의 계정으로 보장한다.
 */
class SetupSecurityTest {

    @Test
    void 인증이_필요한_api_경로에_있다() {
        String path = SetupController.class.getAnnotation(RequestMapping.class).value()[0];

        assertThat(path).startsWith("/api/v1");
        assertThat(path).doesNotStartWith("/downloads");
    }

    @Test
    void 일반_사용자_기능이라_ADMIN_제한이_없다() {
        assertThat(SetupController.class.getAnnotation(PreAuthorize.class)).isNull();
    }
}
