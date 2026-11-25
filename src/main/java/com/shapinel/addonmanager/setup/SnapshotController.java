package com.shapinel.addonmanager.setup;

import com.shapinel.addonmanager.security.jwt.JwtClaims;
import com.shapinel.addonmanager.setup.domain.param.SnapshotParams.AutoParam;
import com.shapinel.addonmanager.setup.domain.param.SnapshotParams.ManualParam;
import com.shapinel.addonmanager.setup.domain.response.SnapshotResponses.SnapshotResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 보관함(내 애드온 목록의 기록). 로그인한 사용자 본인의 기록만 다룬다(사용자 ID 는 토큰에서만 꺼낸다). */
@SecurityRequirement(name = "access-token")
@RestController
@RequiredArgsConstructor
@Tag(name = "[자료실][보관함]")
@RequestMapping("/api/v1/snapshots")
public class SnapshotController {

    private final SnapshotService snapshotService;

    @Operation(summary = "보관함 목록", description = "내 애드온 목록의 기록(자동 기록과 직접 보관한 기록), 최근 것부터")
    @GetMapping
    public ResponseEntity<List<SnapshotResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(snapshotService.list(JwtClaims.userId(jwt)));
    }

    @Operation(summary = "현재 목록 자동 기록", description = "앱이 폴더를 읽은 결과를 알린다. 직전 기록과 같거나 비어 있으면 남기지 않는다.")
    @PostMapping("/auto")
    public ResponseEntity<Void> auto(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid AutoParam param) {
        snapshotService.saveAuto(JwtClaims.userId(jwt), param.getEnvironmentId(), param.getAddons());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "이름 붙여 보관", description = "지금 목록을 이름을 붙여 보관한다(지울 때까지 유지)")
    @PostMapping
    public ResponseEntity<SnapshotResponse> manual(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ManualParam param) {
        return ResponseEntity.ok(snapshotService.saveManual(JwtClaims.userId(jwt), param.getEnvironmentId(), param.getLabel(), param.getAddons()));
    }

    @Operation(summary = "기록 지우기", description = "내 기록만 지울 수 있다")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        snapshotService.delete(JwtClaims.userId(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
