package com.shapinel.addonmanager.admin.release;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앱 배포 권한 정책
 * - 배포 API 는 컨트롤러 전체가 ADMIN 권한으로 보호된다. 이 앱은 사용자 PC 에서 자동 업데이트되므로
 *   권한이 풀리면 모든 사용자 PC 에 코드가 배포될 수 있다. 메서드를 추가하다가 보호가 빠지는 실수를 막는다.
 */
class AdminReleaseSecurityTest {

    @Test
    void 배포_컨트롤러는_클래스_전체가_ADMIN_권한으로_보호된다() {
        PreAuthorize preAuthorize = AdminReleaseController.class.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ADMIN')");
    }

    @Test
    void 배포_API_는_관리자_경로_아래에_있다() {
        var mapping = AdminReleaseController.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);

        assertThat(mapping.value()).containsExactly("/api/v1/admin/app-release");
    }
}
