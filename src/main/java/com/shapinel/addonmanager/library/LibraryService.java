package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.LibraryAddonResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 애드온 자료실: 관리자가 올린 애드온 폴더(zip)를 서버 폴더에 보관하고 로그인한 사용자에게 내려준다.
 * 파일 입출력만 하고 DB 는 LibraryStore 에 맡기므로 이 클래스에는 트랜잭션을 두지 않는다.
 *
 * <p>보안: 저장 파일 이름은 서버가 만든 UUID 만 쓰고 클라이언트가 준 이름·경로는 쓰지 않는다. 자료실에서의 이름은
 * 요청값이 아니라 검사를 통과한 zip 의 최상위 폴더에서 가져온다. 올라온 zip 은 AddonZipInspector 로 검사한 것만 저장한다.
 * 내려주는 것은 인증된 요청에만 허용한다(컨트롤러가 보장).
 */
@Slf4j
@Service
public class LibraryService {

    /** 프록시(Cloudflare)의 요청 크기 제한(100MB)보다 작게 둔다. 애드온 하나의 zip 은 보통 수십 MB 이하다. */
    static final long MAX_ZIP_BYTES = 90L * 1024 * 1024;
    private static final Pattern PUBLIC_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final int MAX_NOTE = 500;

    private final Path storageDir;       // null 이면 기능 비활성
    private final LibraryStore store;

    @Autowired
    public LibraryService(@Value("${app.library-dir:}") String storageDir, LibraryStore store) {
        this(storageDir.isBlank() ? null : Path.of(storageDir), store);
    }

    LibraryService(Path storageDir, LibraryStore store) {
        this.storageDir = storageDir;
        this.store = store;
    }

    public List<LibraryAddonResponse> list() {
        return store.findAll().stream().map(LibraryService::toResponse).toList();
    }

    /**
     * zip(애드온 폴더 하나)을 받아 검사하고 저장한다. 같은 폴더 이름이 있으면 그 항목을 새 파일로 바꾼다(업데이트).
     *
     * @param body 요청 본문(zip). 크기 상한을 넘으면 거부한다.
     */
    public LibraryAddonResponse upload(Long adminId, String note, InputStream body) {
        requireEnabled();
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && (cleanNote.length() > MAX_NOTE || cleanNote.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n'))) {
            throw bad("메모는 " + MAX_NOTE + "자 이하의 글이어야 합니다.");
        }

        Path incoming = null;
        try {
            Files.createDirectories(storageDir);
            incoming = storageDir.resolve("incoming-" + UUID.randomUUID() + ".part");
            copyWithLimit(body, incoming);
            long size = Files.size(incoming);
            if (size == 0) throw bad("빈 파일입니다.");

            AddonZipInspector.Inspection inspected = AddonZipInspector.inspect(incoming);

            String publicId = store.findByName(inspected.folder()).map(LibraryAddonEntity::getPublicId)
                    .orElseGet(() -> UUID.randomUUID().toString());
            moveReplacing(incoming, storageDir.resolve(publicId + ".zip"));
            incoming = null;

            LibraryAddonEntity saved;
            try {
                saved = store.upsert(publicId, inspected.folder(), inspected.version(), inspected.contentHash(),
                        String.join(",", inspected.requires()), size, cleanNote, adminId);
            } catch (RuntimeException e) {
                // 메타데이터를 저장하지 못했으면 새로 둔 파일은 남기지 않는다(없는 항목의 파일이 쌓이지 않게)
                if (store.findByPublicId(publicId).isEmpty()) Files.deleteIfExists(storageDir.resolve(publicId + ".zip"));
                throw e;
            }
            log.info("자료실 업로드: name={}, version={}, size={}, adminId={}", inspected.folder(), inspected.version(), size, adminId);
            return toResponse(saved);
        } catch (IOException e) {
            log.error("자료실 저장 실패", e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "파일을 저장하지 못했습니다.");
        } finally {
            if (incoming != null) {
                try {
                    Files.deleteIfExists(incoming);
                } catch (IOException ignored) {
                    // 임시 파일 정리 실패는 다음 업로드에 영향이 없다
                }
            }
        }
    }

    /** 내려줄 파일. 목록에 없는 ID 이거나 파일이 없으면 NOT_FOUND. */
    public Resource download(String publicId) {
        requireEnabled();
        String id = requirePublicId(publicId);
        store.findByPublicId(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Path file = storageDir.resolve(id + ".zip");
        if (!Files.isRegularFile(file)) throw new BusinessException(ErrorCode.NOT_FOUND, "파일을 찾을 수 없습니다.");
        return new FileSystemResource(file);
    }

    /**
     * 관리자가 같이 설치할 애드온을 지정한다(.toc 에 없는 의존성 보충). 폴더 이름만 받고 경로 문자는 거부한다.
     * 이름이 자료실에 있는지는 확인하지 않는다(나중에 올릴 수도 있다). 사용자 쪽에서 없으면 "자료실에 없음"으로 알린다.
     */
    public LibraryAddonResponse setExtraRequires(String publicId, List<String> names) {
        requireEnabled();
        String id = requirePublicId(publicId);
        List<String> clean = names.stream().map(n -> n == null ? "" : n.trim()).distinct().toList();
        if (clean.size() > AddonZipInspector.MAX_REQUIRES) throw bad("같이 설치할 애드온은 " + AddonZipInspector.MAX_REQUIRES + "개까지 지정할 수 있습니다.");
        for (String n : clean) {
            if (!AddonZipInspector.isSafeFolderName(n)) throw bad("올바른 폴더 이름이 아닙니다: " + (n.length() > 40 ? n.substring(0, 40) + "..." : n));
        }
        LibraryAddonEntity entity = store.updateExtraRequires(id, clean.isEmpty() ? null : String.join(",", clean))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return toResponse(entity);
    }

    public void delete(String publicId, Long adminId) {
        requireEnabled();
        String id = requirePublicId(publicId);
        LibraryAddonEntity entity = store.findByPublicId(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        store.delete(id);
        try {
            Files.deleteIfExists(storageDir.resolve(id + ".zip"));
        } catch (IOException e) {
            log.warn("자료실 파일을 지우지 못했습니다: {}", id, e);
        }
        log.info("자료실 삭제: name={}, adminId={}", entity.getName(), adminId);
    }

    // ---------------------------------------------------------------------------------------------

    private static LibraryAddonResponse toResponse(LibraryAddonEntity e) {
        List<String> fromToc = splitNames(e.getRequires());
        List<String> extra = splitNames(e.getExtraRequires());
        // 사용자가 받는 "같이 설치할 애드온" = .toc 가 요구하는 것 + 관리자가 지정한 것(중복 제거, 자기 자신 제외)
        List<String> all = Stream.concat(fromToc.stream(), extra.stream())
                .filter(n -> !n.equalsIgnoreCase(e.getName()))
                .distinct().toList();
        return new LibraryAddonResponse(e.getPublicId(), e.getName(), e.getVersion(), e.getContentHash(), all, extra,
                e.getFileSize(), e.getNote(), e.getUpdatedAt());
    }

    private static List<String> splitNames(String csv) {
        return csv == null ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 본문을 파일로 저장한다. 상한을 넘으면 거부한다. */
    private static void copyWithLimit(InputStream body, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ZIP_BYTES) throw bad("파일이 너무 큽니다(최대 " + (MAX_ZIP_BYTES / 1024 / 1024) + "MB).");
                out.write(buffer, 0, read);
            }
        }
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** UUID 형식만 받는다. 파일 이름이 이 값에서 나오므로 경로 문자가 섞이면 안 된다. */
    private static String requirePublicId(String publicId) {
        if (publicId == null || !PUBLIC_ID.matcher(publicId).matches()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return publicId;
    }

    private void requireEnabled() {
        if (storageDir == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "자료실 저장 폴더가 설정되지 않았습니다. 서버의 LIBRARY_DIR 환경변수를 확인하세요.");
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
