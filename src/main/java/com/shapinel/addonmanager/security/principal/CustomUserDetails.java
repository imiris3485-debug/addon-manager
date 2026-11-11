package com.shapinel.addonmanager.security.principal;

import com.shapinel.addonmanager.common.domain.entity.AccountEntity;
import com.shapinel.addonmanager.common.domain.entity.AccountEntity.Status;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
public class CustomUserDetails implements UserDetails {

    private final Long id;
    private final String loginId;
    private final String password;
    private final String role;
    private final Status status;

    public CustomUserDetails(AccountEntity account) {
        this.id = account.getId();
        this.loginId = account.getLoginId();
        this.password = account.getPassword();
        this.role = account.getRole().name();
        this.status = account.getStatus();
    }

    /** 접두어(ROLE_) 없이 역할명 그대로 권한으로 사용한다. */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role));
    }

    @Override
    public String getUsername() {
        return loginId;
    }

    // ⚠️ isEnabled 로 승인 여부를 막지 않는다. Spring 은 비밀번호 검사 전에 isEnabled 를 확인하므로
    //    비밀번호를 모르는 사람도 "승인 대기" 응답으로 계정 존재를 알아낼 수 있다.
    //    승인 상태는 인증(비밀번호 일치) 성공 후 AuthService 에서 확인한다.
}
