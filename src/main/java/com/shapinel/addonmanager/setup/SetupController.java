package com.shapinel.addonmanager.setup;

import com.shapinel.addonmanager.security.jwt.JwtClaims;
import com.shapinel.addonmanager.setup.domain.param.SetupParams.InstallEventsParam;
import com.shapinel.addonmanager.setup.domain.param.SetupParams.SetupSaveParam;
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.EventResponse;
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.InstalledResponse;
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.PersonResponse;
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.SetupListResponse;
import com.shapinel.addonmanager.setup.domain.response.SetupResponses.SetupResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 세팅 공유와 설치 기록. 로그인한 사용자 본인의 데이터만 다룬다(사용자 ID 는 토큰에서만 꺼낸다). */
@SecurityRequirement(name = "access-token")
@RestController
@RequiredArgsConstructor
@Tag(name = "[자료실][세팅 공유]")
@RequestMapping("/api/v1")
public class SetupController {

    private final SetupService setupService;
    private final InstallService installService;

    @Operation(summary = "세팅 목록", description = "내가 만든 세팅과 다른 사람이 나에게 공유한 세팅")
    @GetMapping("/setups")
    public ResponseEntity<SetupListResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(setupService.list(JwtClaims.userId(jwt)));
    }

    @Operation(summary = "공유 대상 후보", description = "세팅을 공유할 수 있는 사람(승인된 다른 계정)")
    @GetMapping("/setups/people")
    public ResponseEntity<List<PersonResponse>> people(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(setupService.people(JwtClaims.userId(jwt)));
    }

    @Operation(summary = "세팅 만들기", description = "애드온 묶음에 이름을 붙이고, 지정한 사람에게 공유한다")
    @PostMapping("/setups")
    public ResponseEntity<SetupResponse> create(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid SetupSaveParam param) {
        return ResponseEntity.ok(setupService.create(JwtClaims.userId(jwt), param));
    }

    @Operation(summary = "세팅 고치기", description = "만든 사람만 고칠 수 있다(애드온·설명·공유 대상)")
    @PutMapping("/setups/{id}")
    public ResponseEntity<SetupResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                @RequestBody @Valid SetupSaveParam param) {
        return ResponseEntity.ok(setupService.update(JwtClaims.userId(jwt), id, param));
    }

    @Operation(summary = "세팅 지우기", description = "만든 사람만 지울 수 있다")
    @DeleteMapping("/setups/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        setupService.delete(JwtClaims.userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "설치 결과 알리기", description = "앱이 설치·업데이트·삭제한 뒤 내 설치 현황과 이력에 기록한다")
    @PostMapping("/installs/events")
    public ResponseEntity<Void> record(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid InstallEventsParam param) {
        installService.record(JwtClaims.userId(jwt), param.getEnvironmentId(), param.getEvents());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "내 설치 현황", description = "게임 환경에 설치했다고 기록된 자료실 애드온")
    @GetMapping("/installs")
    public ResponseEntity<List<InstalledResponse>> installed(@AuthenticationPrincipal Jwt jwt, @RequestParam Long environmentId) {
        return ResponseEntity.ok(installService.installed(JwtClaims.userId(jwt), environmentId));
    }

    @Operation(summary = "내 설치·삭제 이력", description = "최근 100건")
    @GetMapping("/installs/history")
    public ResponseEntity<List<EventResponse>> history(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(installService.history(JwtClaims.userId(jwt)));
    }
}
