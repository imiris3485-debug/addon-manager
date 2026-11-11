package com.shapinel.addonmanager.addon;

import com.shapinel.addonmanager.addon.domain.param.AddonParams.InstallReportParam;
import com.shapinel.addonmanager.addon.domain.param.AddonParams.ScanParam;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.ScanResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.SearchResponse;
import com.shapinel.addonmanager.curseforge.CurseForgeClient;
import com.shapinel.addonmanager.security.jwt.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@SecurityRequirement(name = "access-token")
@RestController
@RequiredArgsConstructor
@Tag(name = "[애드온]")
@RequestMapping("/api/v1")
public class AddonController {

    private final AddonService addonService;

    @Operation(summary = "게임 버전 목록", description = "CurseForge 가 지원하는 WoW 게임 버전(종류별, 최신순)")
    @GetMapping("/addons/game-versions")
    public ResponseEntity<List<CurseForgeClient.GameVersionGroup>> gameVersions() {
        return ResponseEntity.ok(addonService.getGameVersions());
    }

    @Operation(summary = "애드온 검색", description = "CurseForge 에서 환경의 게임 버전에 맞는 애드온 검색(인기순)")
    @GetMapping("/addons/search")
    public ResponseEntity<SearchResponse> search(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestParam Long environmentId,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(addonService.search(JwtClaims.userId(jwt), environmentId, q, page, pageSize));
    }

    @Operation(summary = "애드온 파일 다운로드", description = "CurseForge 파일을 서버가 받아 zip 으로 전달(API 키 보호)")
    @GetMapping("/addons/{projectId}/files/{fileId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long projectId, @PathVariable Long fileId) {
        var result = addonService.download(projectId, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(result.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(result.bytes());
    }

    @Operation(summary = "폴더 스캔 결과 판정", description = "브라우저가 스캔한 폴더 목록으로 애드온 매칭과 업데이트 여부 판정")
    @PostMapping("/environments/{environmentId}/scan")
    public ResponseEntity<ScanResponse> scan(@AuthenticationPrincipal Jwt jwt, @PathVariable Long environmentId,
                                             @RequestBody @Valid ScanParam param) {
        return ResponseEntity.ok(addonService.scan(JwtClaims.userId(jwt), environmentId, param));
    }

    @Operation(summary = "설치 결과 보고", description = "브라우저가 설치/업데이트를 마친 뒤 설치된 파일 정보를 기록")
    @PostMapping("/environments/{environmentId}/installed")
    public ResponseEntity<Void> reportInstalled(@AuthenticationPrincipal Jwt jwt, @PathVariable Long environmentId,
                                                @RequestBody @Valid InstallReportParam param) {
        addonService.reportInstalled(JwtClaims.userId(jwt), environmentId, param);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "설치 기록 삭제", description = "브라우저가 폴더를 지운 뒤 기록에서 제거")
    @DeleteMapping("/environments/{environmentId}/installed/{projectId}")
    public ResponseEntity<Void> removeInstalled(@AuthenticationPrincipal Jwt jwt, @PathVariable Long environmentId,
                                                @PathVariable Long projectId) {
        addonService.removeInstalled(JwtClaims.userId(jwt), environmentId, projectId);
        return ResponseEntity.ok().build();
    }
}
