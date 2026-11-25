package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.library.domain.param.LibraryParams.RequiresParam;
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

    @Operation(summary = "자료실에 올리기",
            description = "본문(octet-stream)에 애드온 폴더 하나를 묶은 zip 을 담아 보낸다. 자료실 이름은 zip 의 최상위 폴더 이름이며 "
                    + "같은 폴더가 있으면 새 내용으로 바뀐다. 실행 파일·경로 조작이 든 zip 은 거부한다")
    @PostMapping
    public ResponseEntity<LibraryAddonResponse> upload(@AuthenticationPrincipal Jwt jwt,
                                                       @RequestParam(required = false) String note,
                                                       HttpServletRequest request) throws IOException {
        return ResponseEntity.ok(libraryService.upload(JwtClaims.userId(jwt), note, request.getInputStream()));
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
