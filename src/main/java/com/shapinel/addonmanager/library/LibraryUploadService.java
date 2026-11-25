package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.library.LibraryLayout.FileSpec;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.BeginResponse;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.ChunkResponse;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.LibraryAddonResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 자료실에 애드온을 올린다. 크기 제한 없이 올릴 수 있게 zip 한 덩어리가 아니라 <b>파일 단위</b>로 나눠 올린다.
 *
 * <ol>
 *   <li>begin: 애드온의 파일 목록(경로·크기·SHA-256)을 보낸다. 서버가 목록을 검사하고, 이전 버전에 같은 파일(경로·크기·해시가 같음)이
 *       있으면 그대로 가져와서 <b>바뀐 파일만</b> 올리게 한다.</li>
 *   <li>chunk: 올려야 할 파일을 조각(최대 16MB)으로 이어 올린다. 파일을 다 받으면 SHA-256 을 확인한다.</li>
 *   <li>complete: 모든 파일이 올라왔는지 확인하고 내용 해시를 계산해 자료실 폴더로 옮긴다(옛 버전은 통째로 교체).</li>
 * </ol>
 *
 * <p>보안: 이 파일들은 받은 사람들의 AddOns 폴더에 그대로 쓰인다. 그래서 목록 단계에서 경로 조작·Windows 특수 이름·실행 파일
 * (허용 확장자만 통과)·파일 수·크기를 거부하고, 목록에 없는 경로는 받지 않으며, 파일이 선언한 크기·해시와 다르면 버린다.
 * 파일 이름은 목록의 검사된 경로로만 만들고(요청이 준 경로를 그대로 쓰지 않음) 만들기 직전에 폴더 밖으로 나가지 않는지 한 번 더 확인한다.
 * 올리기 세션은 만든 관리자만 이어갈 수 있다(컨트롤러는 ADMIN 전용).
 */
@Slf4j
@Service
public class LibraryUploadService {

    /** 파일 하나의 상한. 사용자 앱이 파일 하나를 통째로 메모리에 받아 검증·저장하므로 앱의 파일 상한(300MB)보다 작게 둔다. */
    static final long MAX_FILE_BYTES = 250L * 1024 * 1024;
    /** 애드온 하나의 파일 크기 합 상한(서버 디스크를 보호하려는 안전선) */
    static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;
    static final int MAX_FILES = 20_000;
    /** 프록시(Cloudflare)의 요청 크기 제한(100MB)보다 훨씬 작게 둔다 */
    static final int MAX_CHUNK_BYTES = 16 * 1024 * 1024;
    private static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final int MAX_NOTE = 500;
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern UPLOAD_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** 올리려는 파일 한 개의 선언 */
    public record UploadFile(String path, long size, String sha256) {
    }

    private static final class Session {
        final String id;
        final Long adminId;
        final String folder;
        final String note;
        final String publicId;
        final Path dir;
        final Map<String, FileSpec> specs;
        final Set<String> done = ConcurrentHashMap.newKeySet();
        final Instant createdAt = Instant.now();

        Session(String id, Long adminId, String folder, String note, String publicId, Path dir, Map<String, FileSpec> specs) {
            this.id = id;
            this.adminId = adminId;
            this.folder = folder;
            this.note = note;
            this.publicId = publicId;
            this.dir = dir;
            this.specs = specs;
        }
    }

    private final Path root;       // null 이면 기능 비활성
    private final LibraryStore store;
    private final LibraryService libraryService;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Autowired
    public LibraryUploadService(@Value("${app.library-dir:}") String dir, LibraryStore store, LibraryService libraryService) {
        this(dir.isBlank() ? null : Path.of(dir), store, libraryService);
    }

    LibraryUploadService(Path root, LibraryStore store, LibraryService libraryService) {
        this.root = root;
        this.store = store;
        this.libraryService = libraryService;
        discardLeftovers();
    }

    /** 세션은 메모리에만 있으므로, 서버가 다시 시작되면 올리다 만 파일과 교체된 옛 버전은 쓸모가 없어 지운다. */
    private void discardLeftovers() {
        if (root == null) return;
        for (String name : List.of(LibraryLayout.STAGING, LibraryLayout.TRASH)) {
            try {
                LibraryLayout.deleteTree(root.resolve(name));
            } catch (IOException e) {
                log.warn("자료실 임시 폴더를 지우지 못했습니다: {}", name, e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    public BeginResponse begin(Long adminId, String folderRaw, String note, List<UploadFile> files) {
        requireEnabled();
        sweepExpired();

        String folder = folderRaw == null ? "" : folderRaw.trim();
        String[] folderParts = AddonZipInspector.validatePath(folder);
        if (folderParts.length != 1 || !AddonZipInspector.isSafeFolderName(folder)) throw bad("폴더 이름이 올바르지 않습니다.");
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && (cleanNote.length() > MAX_NOTE || cleanNote.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n'))) {
            throw bad("메모는 " + MAX_NOTE + "자 이하의 글이어야 합니다.");
        }
        if (files == null || files.isEmpty()) throw bad("파일이 없습니다.");
        if (files.size() > MAX_FILES) throw bad("파일이 너무 많습니다(최대 " + MAX_FILES + "개).");

        // 경로순으로 정렬해 두면 내용 해시 계산·목록 저장이 항상 같은 순서다
        Map<String, FileSpec> specs = new TreeMap<>();
        Set<String> seenLower = new HashSet<>();
        long total = 0;
        boolean hasToc = false;
        for (UploadFile f : files) {
            String raw = f.path() == null ? "" : f.path();
            if (raw.endsWith("/")) throw bad("파일 경로가 올바르지 않습니다.");
            String[] parts = AddonZipInspector.validatePath(raw);
            String path = String.join("/", parts);
            AddonZipInspector.checkExtension(path, parts[parts.length - 1]);
            // 같은 경로(대소문자 무시)가 둘이면 Windows 에서 어느 것이 남을지 모호하다
            if (!seenLower.add(path.toLowerCase(Locale.ROOT))) throw bad("같은 경로가 두 번 들어 있습니다: " + AddonZipInspector.shorten(path));
            if (f.size() < 0 || f.size() > MAX_FILE_BYTES) {
                throw bad("파일이 너무 큽니다(파일 하나당 최대 " + (MAX_FILE_BYTES / 1024 / 1024) + "MB): " + AddonZipInspector.shorten(path));
            }
            String sha = f.sha256() == null ? "" : f.sha256().toLowerCase(Locale.ROOT);
            if (!HEX64.matcher(sha).matches()) throw bad("파일 해시가 올바르지 않습니다: " + AddonZipInspector.shorten(path));
            total += f.size();
            if (total > MAX_TOTAL_BYTES) throw bad("애드온이 너무 큽니다(최대 " + (MAX_TOTAL_BYTES / 1024 / 1024 / 1024) + "GB).");
            if (parts.length == 1 && AddonZipInspector.isTocFileFor(folder, parts[0])) hasToc = true;
            specs.put(path, new FileSpec(path, f.size(), sha));
        }
        if (!hasToc) throw bad("애드온 폴더에 .toc 파일이 없습니다. 올바른 애드온 폴더가 아닙니다.");

        Optional<LibraryAddonEntity> existing = store.findByName(folder);
        String publicId = existing.map(LibraryAddonEntity::getPublicId).orElseGet(() -> UUID.randomUUID().toString());
        Path prevDir = LibraryLayout.itemDir(root, publicId);
        Map<String, FileSpec> previous = new TreeMap<>();
        try {
            if (Files.exists(prevDir.resolve(LibraryLayout.MANIFEST))) {
                for (FileSpec p : LibraryLayout.readManifest(prevDir)) previous.put(p.path(), p);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("이전 파일 목록을 읽지 못해 처음부터 올립니다: {}", folder, e);
            previous.clear();
        }

        String uploadId = UUID.randomUUID().toString();
        Path dir = root.resolve(LibraryLayout.STAGING).resolve(uploadId);
        Session session = new Session(uploadId, adminId, folder, cleanNote, publicId, dir, specs);
        List<String> needed = new ArrayList<>();
        try {
            Path data = LibraryLayout.dataDir(dir);
            Files.createDirectories(data);
            for (FileSpec spec : specs.values()) {
                FileSpec old = previous.get(spec.path());
                if (old != null && old.size() == spec.size() && old.sha256().equals(spec.sha256())
                        && reuse(LibraryLayout.dataDir(prevDir), data, spec.path())) {
                    session.done.add(spec.path());
                } else if (spec.size() == 0) {
                    if (!spec.sha256().equals(LibraryLayout.emptySha256())) throw bad("빈 파일의 해시가 맞지 않습니다: " + AddonZipInspector.shorten(spec.path()));
                    Path target = LibraryLayout.resolveInside(data, spec.path());
                    Files.createDirectories(target.getParent());
                    Files.write(target, new byte[0]);
                    session.done.add(spec.path());
                } else {
                    needed.add(spec.path());
                }
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(dir);
            if (e instanceof BusinessException be) throw be;
            log.error("올리기 준비 실패: {}", folder, e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "올리기를 준비하지 못했습니다.");
        }
        sessions.put(uploadId, session);
        log.info("자료실 올리기 시작: name={}, files={}, needed={}, adminId={}", folder, specs.size(), needed.size(), adminId);
        return new BeginResponse(uploadId, needed);
    }

    /** 파일 조각을 이어 붙인다. offset 은 지금까지 받은 바이트 수와 같아야 한다(끊겼을 때 이어 올리는 위치). */
    public ChunkResponse chunk(Long adminId, String uploadId, String rawPath, long offset, InputStream body) {
        Session s = session(adminId, uploadId);
        String path = String.join("/", AddonZipInspector.validatePath(rawPath == null ? "" : rawPath));
        FileSpec spec = s.specs.get(path);
        if (spec == null) throw bad("올리기 목록에 없는 파일입니다: " + AddonZipInspector.shorten(path));

        synchronized (s) {
            if (s.done.contains(path)) return new ChunkResponse(spec.size(), true);
            Path file = LibraryLayout.resolveInside(LibraryLayout.dataDir(s.dir), path);
            try {
                long current = Files.exists(file) ? Files.size(file) : 0;
                if (offset != current) throw new BusinessException(ErrorCode.CONFLICT, "다음에 올릴 위치는 " + current + " 입니다.");

                byte[] data = body.readNBytes(MAX_CHUNK_BYTES + 1);
                if (data.length == 0) throw bad("비어 있는 조각입니다.");
                if (data.length > MAX_CHUNK_BYTES) throw bad("한 번에 올릴 수 있는 크기를 넘었습니다(최대 " + (MAX_CHUNK_BYTES / 1024 / 1024) + "MB).");
                if (current + data.length > spec.size()) {
                    Files.deleteIfExists(file);        // 선언한 크기보다 크면 이 파일은 처음부터 다시 올리게 한다
                    throw bad("선언한 크기보다 큰 파일입니다. 이 파일을 처음부터 다시 올려 주세요.");
                }
                Files.createDirectories(file.getParent());
                Files.write(file, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                long received = current + data.length;
                if (received < spec.size()) return new ChunkResponse(received, false);
                if (!LibraryLayout.sha256Hex(file).equals(spec.sha256())) {
                    Files.deleteIfExists(file);
                    throw bad("파일 내용이 올라오는 도중 달라졌습니다. 이 파일을 처음부터 다시 올려 주세요: " + AddonZipInspector.shorten(path));
                }
                s.done.add(path);
                return new ChunkResponse(received, true);
            } catch (IOException e) {
                log.error("올리는 파일을 저장하지 못했습니다: {}", path, e);
                throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "파일을 저장하지 못했습니다.");
            }
        }
    }

    public LibraryAddonResponse complete(Long adminId, String uploadId) {
        Session s = session(adminId, uploadId);
        synchronized (s) {
            List<String> missing = s.specs.keySet().stream().filter(p -> !s.done.contains(p)).toList();
            if (!missing.isEmpty()) {
                throw bad("아직 올라오지 않은 파일이 " + missing.size() + "개 있습니다: " + AddonZipInspector.shorten(missing.get(0)));
            }
            Path itemDir = LibraryLayout.itemDir(root, s.publicId);
            Path trash = root.resolve(LibraryLayout.TRASH).resolve(UUID.randomUUID().toString());
            boolean hadOld = Files.exists(itemDir, LinkOption.NOFOLLOW_LINKS);
            LibraryAddonEntity saved;
            try {
                List<String> tocs = s.specs.keySet().stream().filter(p -> !p.contains("/") && AddonZipInspector.isTocFileFor(s.folder, p)).toList();
                AddonZipInspector.Toc toc = AddonZipInspector.mergeTocFiles(LibraryLayout.dataDir(s.dir), tocs);
                String hash = LibraryLayout.contentHash(s.folder, s.specs.values());
                long total = s.specs.values().stream().mapToLong(FileSpec::size).sum();
                LibraryLayout.writeManifest(s.dir, s.specs.values());

                // 옛 버전을 옆으로 치우고 새 폴더를 제자리에 옮긴다. DB 저장이 실패하면 되돌린다(자료실이 반쪽이 되지 않게).
                Files.createDirectories(trash.getParent());
                if (hadOld) moveReplacing(itemDir, trash);
                try {
                    moveReplacing(s.dir, itemDir);
                    saved = store.upsert(s.publicId, s.folder, toc.version(), hash, String.join(",", toc.requires()), total, s.note, adminId);
                } catch (IOException | RuntimeException e) {
                    LibraryLayout.deleteTree(itemDir);
                    if (hadOld) moveReplacing(trash, itemDir);
                    throw e;
                }
            } catch (IOException e) {
                log.error("자료실에 올리기를 마치지 못했습니다: {}", s.folder, e);
                throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "자료실에 저장하지 못했습니다.");
            }
            deleteQuietly(trash);
            libraryService.invalidate(s.publicId);
            sessions.remove(s.id);
            log.info("자료실 올리기 완료: name={}, files={}, adminId={}", s.folder, s.specs.size(), adminId);
            return LibraryService.toResponse(saved);
        }
    }

    public void abort(Long adminId, String uploadId) {
        Session s = session(adminId, uploadId);
        sessions.remove(s.id);
        deleteQuietly(s.dir);
    }

    // ---------------------------------------------------------------------------------------------

    /** 이전 버전의 같은 파일을 새 폴더로 가져온다(하드 링크, 안 되면 복사). 가져오지 못하면 false 라서 새로 올리게 한다. */
    private static boolean reuse(Path prevData, Path data, String path) {
        try {
            Path from = LibraryLayout.resolveInside(prevData, path);
            if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) return false;
            Path to = LibraryLayout.resolveInside(data, path);
            Files.createDirectories(to.getParent());
            try {
                Files.createLink(to, from);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(from, to);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Session session(Long adminId, String uploadId) {
        requireEnabled();
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) throw new BusinessException(ErrorCode.NOT_FOUND);
        Session s = sessions.get(uploadId);
        // 만든 관리자가 아니면 존재를 알리지 않는다
        if (s == null || !s.adminId.equals(adminId)) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (s.createdAt.plus(SESSION_TTL).isBefore(Instant.now())) {
            sessions.remove(s.id);
            deleteQuietly(s.dir);
            throw new BusinessException(ErrorCode.NOT_FOUND, "올리기 시간이 지났습니다. 처음부터 다시 시작해 주세요.");
        }
        return s;
    }

    private void sweepExpired() {
        Instant limit = Instant.now().minus(SESSION_TTL);
        for (Session s : sessions.values()) {
            if (s.createdAt.isBefore(limit) && sessions.remove(s.id, s)) deleteQuietly(s.dir);
        }
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static void deleteQuietly(Path dir) {
        try {
            LibraryLayout.deleteTree(dir);
        } catch (IOException e) {
            log.warn("임시 폴더를 지우지 못했습니다: {}", dir, e);
        }
    }

    private void requireEnabled() {
        if (root == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "자료실 저장 폴더가 설정되지 않았습니다. 서버의 LIBRARY_DIR 환경변수를 확인하세요.");
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
