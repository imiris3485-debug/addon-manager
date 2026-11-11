package com.shapinel.addonmanager.environment;

import com.shapinel.addonmanager.environment.domain.param.EnvironmentParams.EnvironmentSaveParam;
import com.shapinel.addonmanager.environment.domain.response.EnvironmentResponses.EnvironmentResponse;
import com.shapinel.addonmanager.security.jwt.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@SecurityRequirement(name = "access-token")
@RestController
@RequiredArgsConstructor
@Tag(name = "[애드온][게임 환경]")
@RequestMapping("/api/v1/environments")
public class EnvironmentController {

    private final EnvironmentService environmentService;

    @Operation(summary = "게임 환경 목록", description = "내 게임 환경(클라이언트 버전별 AddOns 폴더) 목록")
    @GetMapping
    public ResponseEntity<List<EnvironmentResponse>> getEnvironments(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(environmentService.getEnvironments(accountId(jwt)));
    }

    @Operation(summary = "게임 환경 생성", description = "이름과 CurseForge 게임 버전 필터 값 지정")
    @PostMapping
    public ResponseEntity<EnvironmentResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestBody @Valid EnvironmentSaveParam param) {
        return ResponseEntity.ok(environmentService.create(accountId(jwt), param));
    }

    @Operation(summary = "게임 환경 수정", description = "이름·게임 버전 변경")
    @PutMapping("/{environmentId}")
    public ResponseEntity<Void> update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long environmentId,
                                       @RequestBody @Valid EnvironmentSaveParam param) {
        environmentService.update(accountId(jwt), environmentId, param);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "게임 환경 삭제", description = "환경과 설치 기록 삭제(실제 폴더는 건드리지 않음)")
    @DeleteMapping("/{environmentId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long environmentId) {
        environmentService.delete(accountId(jwt), environmentId);
        return ResponseEntity.ok().build();
    }

    private static Long accountId(Jwt jwt) {
        return JwtClaims.userId(jwt);
    }
}
