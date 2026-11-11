package com.shapinel.addonmanager.admin.account;

import com.shapinel.addonmanager.common.domain.entity.AccountEntity;
import com.shapinel.addonmanager.common.domain.entity.AccountEntity.Role;
import com.shapinel.addonmanager.common.domain.entity.AccountEntity.Status;
import com.shapinel.addonmanager.common.domain.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 최초 관리자 계정 생성. 가입 승인을 할 관리자가 있어야 하므로 환경변수로 한 번 만들어 둔다.
 * 이미 같은 아이디가 있으면 아무것도 하지 않는다(비밀번호를 덮어쓰지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.login-id:}")
    private String loginId;
    @Value("${app.admin.password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        if (loginId.isBlank() || password.isBlank()) {
            return;
        }
        if (accountRepository.existsByLoginId(loginId)) {
            return;
        }
        accountRepository.save(AccountEntity.builder()
                .loginId(loginId)
                .password(passwordEncoder.encode(password))
                .name("관리자")
                .role(Role.ADMIN)
                .status(Status.ACTIVE)
                .build());
        // 비밀번호는 로그에 남기지 않는다
        log.info("최초 관리자 계정 생성: {}", loginId);
    }
}
