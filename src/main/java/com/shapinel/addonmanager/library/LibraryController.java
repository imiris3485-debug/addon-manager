package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.library.domain.response.LibraryResponses.FileResponse;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.LibraryAddonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 애드온 자료실. 로그인한(승인된) 사용자만 목록을 보고 받을 수 있다.
 * 파일을 공개 정적 경로(/downloads 등)로 내주지 않고 반드시 이 인증 경로로만 내려준다.
 */
@SecurityRequirement(name = "access-token")
@RestController
@RequiredArgsConstructor
@Tag(name = "[자료실]")
@RequestMapping("/api/v1/library")
public class LibraryController {

    private final LibraryService libraryService;

    @Operation(summary = "자료실 목록", description = "관리자가 올린 애드온(폴더 이름·버전·내용 해시·필요한 애드온)")
    @GetMapping
    public ResponseEntity<List<LibraryAddonResponse>> list() {
        return ResponseEntity.ok(libraryService.list());
    }

    @Operation(summary = "애드온 파일 목록", description = "애드온의 파일 목록(경로·크기·SHA-256). 받는 쪽이 파일별로 내려받고 해시로 검증한다(로그인 필요)")
    @GetMapping("/{id}/manifest")
    public ResponseEntity<List<FileResponse>> manifest(@PathVariable String id) {
        return ResponseEntity.ok(libraryService.manifest(id));
    }

    @Operation(summary = "애드온 파일 받기", description = "목록에 있는 파일 하나를 내려준다. 경로는 폴더 기준 상대 경로(로그인 필요)")
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable String id, @RequestParam String path) {
        Resource file = libraryService.file(id, path);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("file.bin").build().toString())
                .body(file);
    }
}
