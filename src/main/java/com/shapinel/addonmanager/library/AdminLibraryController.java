package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.library.domain.param.LibraryParams.BeginParam;
import com.shapinel.addonmanager.library.domain.param.LibraryParams.RequiresParam;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.BeginResponse;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.BundleResponse;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.ChunkResponse;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.LibraryAddonResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 자료실 관리. 올리기·지우기는 관리자만 한다.
 * 올린 파일은 받은 사람들의 게임 폴더에 풀리므로 권한이 풀리면 안 된다(AdminLibrarySecurityTest 가 보호를 고정한다).
 */
@SecurityRequirement(name = "access-token")
@PreAuthorize("hasAuthority('ADMIN')")
@RestController
@RequiredArgsConstructor
@Tag(name = "[관리자][자료실]")
@RequestMapping("/api/v1/admin/library")
public class AdminLibraryController {

    private final LibraryService libraryService;
    private final LibraryUploadService uploadService;

    @Operation(summary = "올리기 시작",
            description = "올릴 애드온 폴더의 파일 목록(경로·크기·SHA-256)을 보낸다. 서버가 목록을 검사하고 이전 버전에 같은 파일이 있으면 "
                    + "그대로 가져와서, 새로 올려야 할 파일의 경로만 돌려준다. 실행 파일·경로 조작이 든 목록은 거부한다")
    @PostMapping("/uploads")
    public ResponseEntity<BeginResponse> begin(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid BeginParam param) {
        List<LibraryUploadService.UploadFile> files = param.getFiles().stream()
                .map(f -> new LibraryUploadService.UploadFile(f.getPath(), f.getSize(), f.getSha256())).toList();
        return ResponseEntity.ok(uploadService.begin(JwtClaims.userId(jwt), param.getFolder(), param.getNote(), files));
    }

    @Operation(summary = "파일 조각 올리기",
            description = "올려야 할 파일을 조각(최대 16MB)으로 이어 올린다. offset 은 지금까지 받은 바이트 수와 같아야 하며 "
                    + "파일을 다 받으면 SHA-256 을 확인한다")
    @PutMapping("/uploads/{uploadId}/chunk")
    public ResponseEntity<ChunkResponse> chunk(@AuthenticationPrincipal Jwt jwt, @PathVariable String uploadId,
                                               @RequestParam String path, @RequestParam long offset,
                                               HttpServletRequest request) throws IOException {
        return ResponseEntity.ok(uploadService.chunk(JwtClaims.userId(jwt), uploadId, path, offset, request.getInputStream()));
    }

    @Operation(summary = "작은 파일 묶음 올리기",
            description = "작은 파일 여러 개를 하나로 이어 올린다(파일마다 [경로 길이 2][경로][크기 8][내용], 최대 32MB). "
                    + "파일마다 목록의 경로·크기·SHA-256 을 확인하고, 이미 받은 파일은 건너뛰므로 다시 보내도 안전하다")
    @PutMapping("/uploads/{uploadId}/bundle")
    public ResponseEntity<BundleResponse> bundle(@AuthenticationPrincipal Jwt jwt, @PathVariable String uploadId,
                                                 HttpServletRequest request) throws IOException {
        return ResponseEntity.ok(new BundleResponse(uploadService.bundle(JwtClaims.userId(jwt), uploadId, request.getInputStream())));
    }

    @Operation(summary = "올리기 끝내기", description = "모든 파일이 올라왔는지 확인하고 자료실에 반영한다(같은 폴더가 있으면 새 내용으로 바뀐다)")
    @PostMapping("/uploads/{uploadId}/complete")
    public ResponseEntity<LibraryAddonResponse> complete(@AuthenticationPrincipal Jwt jwt, @PathVariable String uploadId) {
        return ResponseEntity.ok(uploadService.complete(JwtClaims.userId(jwt), uploadId));
    }

    @Operation(summary = "올리기 취소", description = "올리던 임시 파일을 지운다")
    @DeleteMapping("/uploads/{uploadId}")
    public ResponseEntity<Void> abort(@AuthenticationPrincipal Jwt jwt, @PathVariable String uploadId) {
        uploadService.abort(JwtClaims.userId(jwt), uploadId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "같이 설치할 애드온 지정",
            description = "사용자가 이 애드온을 설치할 때 함께 설치할 폴더 이름들을 정한다(.toc 에 선언되지 않은 의존성 보충). "
                    + "비우면 지정을 지운다. 다시 올려도 유지된다")
    @PutMapping("/{id}/requires")
    public ResponseEntity<LibraryAddonResponse> setRequires(@PathVariable String id, @RequestBody @Valid RequiresParam param) {
        return ResponseEntity.ok(libraryService.setExtraRequires(id, param.getRequires()));
    }

    @Operation(summary = "자료실에서 지우기", description = "목록과 서버의 파일을 지운다")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        libraryService.delete(id, JwtClaims.userId(jwt));
        return ResponseEntity.ok().build();
    }
}
