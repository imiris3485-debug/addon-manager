package com.shapinel.addonmanager.addon;

import com.shapinel.addonmanager.addon.domain.param.AddonParams.InstallReportParam;
import com.shapinel.addonmanager.addon.domain.param.AddonParams.ScanParam;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.AddonDetailResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.AddonFileResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.CategoryResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.ChangelogResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.DependencyResponse;
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

    @Operation(summary = "애드온 검색", description = "CurseForge 에서 환경의 게임 버전에 맞는 애드온 검색. 정렬(POPULARITY/UPDATED/DOWNLOADS/NAME/RATING, 기본 인기순)과 분류로 거를 수 있다")
    @GetMapping("/addons/search")
    public ResponseEntity<SearchResponse> search(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestParam Long environmentId,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(required = false) String sort,
                                                 @RequestParam(required = false) Long categoryId,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(addonService.search(JwtClaims.userId(jwt), environmentId, q, page, pageSize, sort, categoryId));
    }

    @Operation(summary = "애드온 분류 목록", description = "검색 필터에 쓰는 CurseForge 애드온 분류(이름순)")
    @GetMapping("/addons/categories")
    public ResponseEntity<List<CategoryResponse>> categories() {
        return ResponseEntity.ok(addonService.getCategories());
    }

    @Operation(summary = "애드온 상세", description = "스크린샷·설명(글자)·분류·링크·갱신일·추천수·라이선스 등 상세 정보")
    @GetMapping("/addons/{projectId}")
    public ResponseEntity<AddonDetailResponse> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable Long projectId,
                                                      @RequestParam Long environmentId) {
        return ResponseEntity.ok(addonService.getDetail(JwtClaims.userId(jwt), environmentId, projectId));
    }

    @Operation(summary = "애드온 파일 목록", description = "환경의 게임 버전용 파일(정식·베타·알파)을 새 것부터. 골라서 설치하거나 이전 버전으로 되돌릴 때 쓴다")
    @GetMapping("/addons/{projectId}/files")
    public ResponseEntity<List<AddonFileResponse>> files(@AuthenticationPrincipal Jwt jwt, @PathVariable Long projectId,
                                                         @RequestParam Long environmentId) {
        return ResponseEntity.ok(addonService.getFiles(JwtClaims.userId(jwt), environmentId, projectId));
    }

    @Operation(summary = "파일 변경 내역", description = "파일의 변경 내역(HTML 을 지운 글자)")
    @GetMapping("/addons/{projectId}/files/{fileId}/changelog")
    public ResponseEntity<ChangelogResponse> changelog(@PathVariable Long projectId, @PathVariable Long fileId) {
        return ResponseEntity.ok(addonService.getChangelog(projectId, fileId));
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

    @Operation(summary = "설치된 프로젝트 목록", description = "이 환경에 설치된 것으로 기록된 프로젝트 ID(검색 화면의 설치됨 표시용)")
    @GetMapping("/environments/{environmentId}/installed")
    public ResponseEntity<List<Long>> installedProjectIds(@AuthenticationPrincipal Jwt jwt, @PathVariable Long environmentId) {
        return ResponseEntity.ok(addonService.getInstalledProjectIds(JwtClaims.userId(jwt), environmentId));
    }

    @Operation(summary = "필수 의존성 조회", description = "파일이 필요로 하는 다른 애드온(필수 의존성, 의존성의 의존성 포함)과 환경의 게임 버전에 맞는 최신 파일")
    @GetMapping("/addons/{projectId}/files/{fileId}/dependencies")
    public ResponseEntity<List<DependencyResponse>> dependencies(@AuthenticationPrincipal Jwt jwt,
                                                                 @PathVariable Long projectId, @PathVariable Long fileId,
                                                                 @RequestParam Long environmentId) {
        return ResponseEntity.ok(addonService.getRequiredDependencies(JwtClaims.userId(jwt), environmentId, projectId, fileId));
    }

    @Operation(summary = "설치 기록 삭제", description = "브라우저가 폴더를 지운 뒤 기록에서 제거")
    @DeleteMapping("/environments/{environmentId}/installed/{projectId}")
    public ResponseEntity<Void> removeInstalled(@AuthenticationPrincipal Jwt jwt, @PathVariable Long environmentId,
                                                @PathVariable Long projectId) {
        addonService.removeInstalled(JwtClaims.userId(jwt), environmentId, projectId);
        return ResponseEntity.ok().build();
    }
}
