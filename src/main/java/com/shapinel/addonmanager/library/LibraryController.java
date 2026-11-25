package com.shapinel.addonmanager.library;

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

    @Operation(summary = "자료실 애드온 받기", description = "애드온 zip(폴더 하나)을 내려준다(로그인 필요)")
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        Resource file = libraryService.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(id + ".zip").build().toString())
                .body(file);
    }
}
