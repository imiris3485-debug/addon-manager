package com.shapinel.addonmanager.account;

import com.shapinel.addonmanager.account.domain.response.AccountResponses.MyInfoResponse;
import com.shapinel.addonmanager.security.jwt.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SecurityRequirement(name = "access-token")
@RestController
@RequiredArgsConstructor
@Tag(name = "[계정]")
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "내 정보 조회", description = "토큰의 사용자 기준으로 내 계정 정보 조회")
    @GetMapping("/me")
    public ResponseEntity<MyInfoResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(accountService.getMyInfo(JwtClaims.userId(jwt)));
    }
}
