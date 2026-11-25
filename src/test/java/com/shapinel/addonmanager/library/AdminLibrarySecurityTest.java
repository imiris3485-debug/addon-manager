package com.shapinel.addonmanager.library;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자료실 권한 정책
 * - 올리기·지우기는 컨트롤러 전체가 ADMIN 권한으로 보호된다. 올린 파일은 받은 사람들의 게임 폴더에 풀리므로
 *   메서드를 추가하다가 보호가 빠지는 실수를 막는다.
 * - 받기·목록은 관리자 경로가 아니라 인증된 사용자 경로에 있고, 공개 정적 경로로 내주지 않는다.
 */
class AdminLibrarySecurityTest {

    @Test
    void 관리_컨트롤러는_클래스_전체가_ADMIN_권한으로_보호된다() {
        PreAuthorize preAuthorize = AdminLibraryController.class.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ADMIN')");
    }

    @Test
    void 관리_API_는_관리자_경로_아래에_있다() {
        assertThat(AdminLibraryController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/admin/library");
    }

    @Test
    void 받기_API_는_인증이_필요한_api_경로에_있고_공개_정적_경로가_아니다() {
        String path = LibraryController.class.getAnnotation(RequestMapping.class).value()[0];

        assertThat(path).startsWith("/api/v1/");                  // SecurityConfig 의 /api/** 는 기본이 인증 필요
        assertThat(path).doesNotStartWith("/downloads");
    }
}
