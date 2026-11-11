package com.shapinel.addonmanager.auth;

import com.shapinel.addonmanager.auth.domain.param.AuthParams.LoginParam;
import com.shapinel.addonmanager.auth.domain.param.AuthParams.SignupParam;
import com.shapinel.addonmanager.auth.domain.response.AuthResponses.LoginResponse;
import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.AccountEntity;
import com.shapinel.addonmanager.common.domain.entity.AccountEntity.Role;
import com.shapinel.addonmanager.common.domain.entity.AccountEntity.Status;
import com.shapinel.addonmanager.common.domain.repository.AccountRepository;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.security.jwt.JwtProvider;
import com.shapinel.addonmanager.security.principal.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtProvider jwtProvider;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    /** 가입은 PENDING 상태로 저장되며, 관리자 승인 전에는 로그인할 수 없다. */
    public void signup(SignupParam param) {
        if (accountRepository.existsByLoginId(param.getLoginId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
        try {
            accountRepository.saveAndFlush(AccountEntity.builder()
                    .loginId(param.getLoginId())
                    .password(passwordEncoder.encode(param.getPassword()))
                    .name(param.getName())
                    .role(Role.USER)            // 권한은 가입 요청값으로 받지 않는다
                    .status(Status.PENDING)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 동시 가입으로 사전 중복 확인을 통과한 경우를 유니크 제약이 막는다
            throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginParam param) {
        CustomUserDetails user;
        try {
            var auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(param.getLoginId(), param.getPassword()));
            user = (CustomUserDetails) auth.getPrincipal();
        } catch (AuthenticationException e) {
            // 아이디 존재 여부를 구분해 알려주지 않는다(계정 열거 방지)
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        // 비밀번호가 맞은 뒤에만 승인 상태를 알려준다
        if (user.getStatus() == Status.PENDING) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "관리자 승인 대기 중입니다.");
        }
        if (user.getStatus() == Status.REJECTED) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "가입이 승인되지 않았습니다.");
        }
        return new LoginResponse(jwtProvider.issueAccessToken(user), jwtProvider.getAccessTokenSeconds());
    }
}
