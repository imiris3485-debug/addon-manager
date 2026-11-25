package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.library.domain.param.LibraryParams.BundleParam;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.FileResponse;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.LibraryAddonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
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

    @Operation(summary = "애드온 파일 묶음 받기",
            description = "목록에 있는 파일들을 하나의 스트림에 이어 담아 내려준다(파일마다 [경로 길이 2][경로][크기 8][내용]). "
                    + "파일이 수천 개여도 요청 한 번으로 받는다(로그인 필요)")
    @PostMapping("/{id}/bundle")
    public void bundle(@PathVariable String id, @RequestBody @Valid BundleParam param, HttpServletResponse response) throws IOException {
        // 검사는 응답을 쓰기 전에 끝낸다(목록에 없는 경로면 404). 스트림은 요청 스레드에서 바로 쓴다.
        var entries = libraryService.prepareBundle(id, param.getPaths());
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        LibraryService.writeBundle(entries, response.getOutputStream());
    }
}
