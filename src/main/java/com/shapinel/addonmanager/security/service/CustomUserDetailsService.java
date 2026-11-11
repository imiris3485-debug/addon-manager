package com.shapinel.addonmanager.security.service;

import com.shapinel.addonmanager.common.domain.repository.AccountRepository;
import com.shapinel.addonmanager.security.principal.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final AccountRepository accountRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        // 비밀번호 해시 등 계정 정보는 로그에 남기지 않는다
        return accountRepository.findByLoginIdAndDeletedAtIsNull(loginId)
                .map(CustomUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("계정을 찾을 수 없습니다."));
    }
}
