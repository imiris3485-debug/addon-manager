package com.shapinel.addonmanager.auth;

import com.shapinel.addonmanager.auth.domain.param.AuthParams.LoginParam;
import com.shapinel.addonmanager.auth.domain.param.AuthParams.SignupParam;
import com.shapinel.addonmanager.auth.domain.response.AuthResponses.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "[인증]")
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "회원가입", description = "가입 요청. 관리자 승인 후 로그인 가능")
    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@RequestBody @Valid SignupParam param) {
        authService.signup(param);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "로그인", description = "아이디·비밀번호로 Access 토큰 발급")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginParam param) {
        return ResponseEntity.ok(authService.login(param));
    }
}
