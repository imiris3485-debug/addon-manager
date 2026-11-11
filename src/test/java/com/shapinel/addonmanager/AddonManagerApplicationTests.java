package com.shapinel.addonmanager;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 컨텍스트 로딩 테스트. MariaDB·MongoDB·MinIO 접속과 환경변수가 필요하므로
 * 로컬 인프라가 준비되기 전까지는 비활성화한다.
 */
@Disabled("DB/MongoDB/MinIO 및 환경변수 준비 후 활성화")
@SpringBootTest
class AddonManagerApplicationTests {

    @Test
    void contextLoads() {
    }

}
