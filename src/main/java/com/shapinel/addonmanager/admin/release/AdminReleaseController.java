package com.shapinel.addonmanager.admin.release;

import com.shapinel.addonmanager.admin.release.domain.param.AdminReleaseParams.CompleteParam;
import com.shapinel.addonmanager.admin.release.domain.response.AdminReleaseResponses.ReleaseResponse;
import com.shapinel.addonmanager.admin.release.domain.response.AdminReleaseResponses.UploadStartResponse;
import com.shapinel.addonmanager.security.jwt.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

import java.io.IOException;

/**
 * 데스크톱 앱 배포. 관리자만 올릴 수 있다. 이 앱은 사용자 PC 에서 실행되고 스스로 업데이트하므로
 * 이 권한이 뚫리면 모든 사용자 PC 에 코드를 배포할 수 있다 — 관리자 계정 보호가 그만큼 중요하다.
 */
@SecurityRequirement(name = "access-token")
@PreAuthorize("hasAuthority('ADMIN')")
@RestController
@RequiredArgsConstructor
@Tag(name = "[관리자][앱 배포]")
@RequestMapping("/api/v1/admin/app-release")
public class AdminReleaseController {

    private final AdminReleaseService releaseService;

    @Operation(summary = "게시된 앱 정보", description = "현재 배포 중인 데스크톱 앱의 버전·크기·게시 시각")
    @GetMapping
    public ResponseEntity<ReleaseResponse> getCurrent() {
        return ResponseEntity.ok(releaseService.getCurrent());
    }

    @Operation(summary = "업로드 시작", description = "설치 파일을 조각으로 나눠 올리기 위한 업로드를 시작하고 식별자·조각 크기를 돌려준다")
    @PostMapping("/uploads")
    public ResponseEntity<UploadStartResponse> start() {
        return ResponseEntity.ok(releaseService.start());
    }

    @Operation(summary = "조각 업로드", description = "설치 파일의 index(0부터) 번째 조각을 본문(octet-stream)으로 보낸다. 순서대로 보내야 한다")
    @PutMapping("/uploads/{uploadId}/chunks/{index}")
    public ResponseEntity<Void> uploadChunk(@PathVariable String uploadId, @PathVariable int index,
                                            HttpServletRequest request) throws IOException {
        releaseService.appendChunk(uploadId, index, request.getInputStream());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "업로드 완료·게시", description = "latest.yml 내용과 대조해 설치 파일을 검증한 뒤 게시한다")
    @PostMapping("/uploads/{uploadId}/complete")
    public ResponseEntity<ReleaseResponse> complete(@AuthenticationPrincipal Jwt jwt, @PathVariable String uploadId,
                                                    @RequestBody @Valid CompleteParam param) {
        return ResponseEntity.ok(releaseService.complete(uploadId, param.getLatestYml(), JwtClaims.userId(jwt)));
    }

    @Operation(summary = "업로드 취소", description = "진행 중인 업로드와 임시 파일을 지운다")
    @DeleteMapping("/uploads/{uploadId}")
    public ResponseEntity<Void> abort(@PathVariable String uploadId) {
        releaseService.abort(uploadId);
        return ResponseEntity.ok().build();
    }
}
