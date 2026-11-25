package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.library.LibraryLayout.FileSpec;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.FileResponse;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.LibraryAddonResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 애드온 자료실: 목록, 내려받기(파일 목록 + 파일별), 지우기, 함께 설치할 애드온 지정. 올리기는 LibraryUploadService 가 맡는다.
 * 파일 입출력만 하고 DB 는 LibraryStore 에 맡기므로 이 클래스에는 트랜잭션을 두지 않는다.
 *
 * <p>보안: 저장 폴더 이름은 서버가 만든 UUID 만 쓰고 클라이언트가 준 이름·경로는 쓰지 않는다. 내려주는 파일은 "목록에 있는 경로"만이고
 * 경로는 한 번 더 폴더 밖으로 나가지 않는지 확인한다. 내려주는 것은 인증된 요청에만 허용한다(컨트롤러가 보장).
 */
@Slf4j
@Service
public class LibraryService {

    private static final Pattern PUBLIC_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final Path storageDir;       // null 이면 기능 비활성
    private final LibraryStore store;
    /** 파일마다 요청이 오므로 매번 목록 파일을 읽지 않도록 항목별로 캐시한다. 올리기·지우기 때 비운다. */
    private final Map<String, Map<String, FileSpec>> manifestCache = new ConcurrentHashMap<>();

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

    /** 애드온의 파일 목록(경로·크기·SHA-256). 받는 쪽이 파일별로 내려받고 해시로 검증한다. */
    public List<FileResponse> manifest(String publicId) {
        String id = requireItem(publicId);
        return specs(id).values().stream().map(f -> new FileResponse(f.path(), f.size(), f.sha256())).toList();
    }

    /** 내려줄 파일 하나. 목록에 없는 경로이거나 파일이 없으면 NOT_FOUND. */
    public Resource file(String publicId, String rawPath) {
        String id = requireItem(publicId);
        // 요청이 준 경로를 파일 이름으로 쓰지 않는다: 검사한 뒤 "목록에 있는 경로"와 정확히 같을 때만 그 파일을 연다.
        String path = String.join("/", AddonZipInspector.validatePath(rawPath == null ? "" : rawPath));
        if (!specs(id).containsKey(path)) throw new BusinessException(ErrorCode.NOT_FOUND);
        Path file = LibraryLayout.resolveInside(LibraryLayout.dataDir(LibraryLayout.itemDir(storageDir, id)), path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new BusinessException(ErrorCode.NOT_FOUND, "파일을 찾을 수 없습니다.");
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
        invalidate(id);
        try {
            LibraryLayout.deleteTree(LibraryLayout.itemDir(storageDir, id));
            Files.deleteIfExists(storageDir.resolve(id + ".zip"));      // 옛 형식(zip)으로 남은 파일
        } catch (IOException e) {
            log.warn("자료실 파일을 지우지 못했습니다: {}", id, e);
        }
        log.info("자료실 삭제: name={}, adminId={}", entity.getName(), adminId);
    }

    /** 항목의 파일이 바뀌었을 때(올리기·지우기) 캐시한 목록을 버린다. */
    void invalidate(String publicId) {
        manifestCache.remove(publicId);
    }

    // ---------------------------------------------------------------------------------------------

    static LibraryAddonResponse toResponse(LibraryAddonEntity e) {
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

    /** 목록에 있는 항목인지 확인하고 ID 를 돌려준다. */
    private String requireItem(String publicId) {
        requireEnabled();
        String id = requirePublicId(publicId);
        store.findByPublicId(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return id;
    }

    private Map<String, FileSpec> specs(String id) {
        Map<String, FileSpec> cached = manifestCache.get(id);
        if (cached != null) return cached;
        try {
            Map<String, FileSpec> loaded = new LinkedHashMap<>();
            for (FileSpec f : LibraryLayout.readManifest(LibraryLayout.itemDir(storageDir, id))) loaded.put(f.path(), f);
            manifestCache.put(id, loaded);
            return loaded;
        } catch (IOException | RuntimeException e) {
            log.warn("자료실 파일 목록을 읽지 못했습니다: {}", id, e);
            throw new BusinessException(ErrorCode.NOT_FOUND, "파일 목록을 찾을 수 없습니다. 관리자에게 알려 주세요.");
        }
    }

    /** UUID 형식만 받는다. 폴더 이름이 이 값에서 나오므로 경로 문자가 섞이면 안 된다. */
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
