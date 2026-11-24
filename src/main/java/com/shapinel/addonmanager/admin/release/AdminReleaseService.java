package com.shapinel.addonmanager.admin.release;

import com.shapinel.addonmanager.admin.release.domain.response.AdminReleaseResponses.ReleaseResponse;
import com.shapinel.addonmanager.admin.release.domain.response.AdminReleaseResponses.UploadStartResponse;
import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 데스크톱 앱(설치 파일 + 업데이트 정보) 배포.
 *
 * <p>설치 파일(100MB 이상)은 프록시(Cloudflare)의 요청 크기 제한을 넘을 수 있어 작은 조각으로 나눠 받는다.
 * 파일 시스템만 다루고 DB 를 쓰지 않으므로 트랜잭션은 두지 않는다.
 *
 * <p>보안: 저장 파일 이름은 서버가 정한 상수만 쓰고 클라이언트가 준 이름·경로는 쓰지 않는다.
 * 업로드 식별자는 UUID 형식만 받아 임시 파일 경로를 조작할 수 없게 한다.
 * 업데이트 정보는 설치 파일의 SHA-512·크기와 서버가 직접 대조한 뒤에만 게시한다.
 */
@Slf4j
@Service
public class AdminReleaseService {

    static final String INSTALLER_NAME = "AddonManager-Setup.exe";
    static final String MANIFEST_NAME = "latest.yml";
    static final long MAX_BYTES = 300L * 1024 * 1024;
    static final int CHUNK_SIZE = 8 * 1024 * 1024;

    private static final Pattern UPLOAD_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Duration STALE_AFTER = Duration.ofHours(1);
    private static final int MAX_ACTIVE_UPLOADS = 3;

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private static final class Upload {
        final Path file;
        final Instant createdAt = Instant.now();
        // 시각은 거의 동시에 만들면 같아질 수 있어, "가장 오래된 것"은 생성 순번으로 정한다
        final long seq = SEQUENCE.incrementAndGet();
        long received;
        int nextIndex;

        Upload(Path file) {
            this.file = file;
        }
    }

    private final Path downloadsDir;     // null 이면 배포 기능 비활성
    private final Path tmpDir;
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    @Autowired
    public AdminReleaseService(@Value("${app.downloads-dir:}") String downloadsDir) {
        this(downloadsDir.isBlank() ? null : Path.of(downloadsDir),
                Path.of(System.getProperty("java.io.tmpdir"), "addon-uploads"));
    }

    AdminReleaseService(Path downloadsDir, Path tmpDir) {
        this.downloadsDir = downloadsDir;
        this.tmpDir = tmpDir;
    }

    public ReleaseResponse getCurrent() {
        if (downloadsDir == null) return new ReleaseResponse(false, null, 0, null);
        Path manifest = downloadsDir.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest) || !Files.isRegularFile(downloadsDir.resolve(INSTALLER_NAME))) {
            return new ReleaseResponse(false, null, 0, null);
        }
        try {
            ReleaseManifest.Published p = ReleaseManifest.readPublished(Files.readString(manifest, StandardCharsets.UTF_8));
            return p == null ? new ReleaseResponse(false, null, 0, null)
                    : new ReleaseResponse(true, p.version(), p.size(), p.releaseDate());
        } catch (IOException e) {
            log.warn("게시된 앱 정보를 읽지 못했습니다", e);
            return new ReleaseResponse(false, null, 0, null);
        }
    }

    public UploadStartResponse start() {
        requireEnabled();
        try {
            Files.createDirectories(tmpDir);
        } catch (IOException e) {
            throw internal("업로드 임시 폴더를 만들 수 없습니다.", e);
        }
        cleanup();
        String id = UUID.randomUUID().toString();
        uploads.put(id, new Upload(tmpDir.resolve(id + ".part")));
        return new UploadStartResponse(id, CHUNK_SIZE, MAX_BYTES);
    }

    /** 조각을 순서대로 이어 붙인다. 순서가 어긋나거나 크기를 넘으면 그 업로드는 폐기하고 처음부터 다시 하게 한다. */
    public void appendChunk(String uploadId, int index, InputStream body) {
        Upload upload = find(uploadId);
        synchronized (upload) {
            if (index != upload.nextIndex) {
                throw new BusinessException(ErrorCode.CONFLICT, "조각 순서가 맞지 않습니다. 처음부터 다시 올려 주세요.");
            }
            try (OutputStream out = Files.newOutputStream(upload.file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                long copied = copyLimited(body, out, CHUNK_SIZE);
                if (copied > CHUNK_SIZE) {
                    discard(uploadId);
                    throw bad("조각이 너무 큽니다.");
                }
                upload.received += copied;
                if (upload.received > MAX_BYTES) {
                    discard(uploadId);
                    throw bad("설치 파일이 너무 큽니다.");
                }
                upload.nextIndex++;
            } catch (IOException e) {
                discard(uploadId);
                throw internal("조각을 저장하지 못했습니다.", e);
            }
        }
    }

    public ReleaseResponse complete(String uploadId, String latestYml, Long adminId) {
        requireEnabled();
        Upload upload = find(uploadId);
        synchronized (upload) {
            try {
                ReleaseManifest.Parsed manifest = ReleaseManifest.parse(latestYml, INSTALLER_NAME, MAX_BYTES);

                if (manifest.declaredSize() != null && upload.received != manifest.declaredSize()) {
                    throw bad("올린 파일 크기가 latest.yml 의 size 와 다릅니다. 같은 빌드의 두 파일을 올려 주세요.");
                }
                requireWindowsExecutable(upload.file);
                if (!manifest.sha512().equals(sha512Base64(upload.file))) {
                    throw bad("올린 파일이 latest.yml 의 sha512 와 다릅니다. 같은 빌드의 두 파일을 올려 주세요.");
                }
                // 실수로 옛 빌드를 올려 모든 사용자가 낮은 버전으로 돌아가는 일을 막는다
                ReleaseResponse current = getCurrent();
                if (current.isPublished() && ReleaseManifest.compareVersions(manifest.version(), current.getVersion()) < 0) {
                    throw new BusinessException(ErrorCode.CONFLICT,
                            "게시된 버전(" + current.getVersion() + ")보다 낮은 버전은 올릴 수 없습니다.");
                }

                publish(upload.file, manifest, upload.received);
                log.info("데스크톱 앱 게시: version={}, size={}, adminId={}", manifest.version(), upload.received, adminId);
                discard(uploadId);
                return getCurrent();
            } catch (IOException e) {
                discard(uploadId);
                throw internal("앱을 게시하지 못했습니다.", e);
            }
            // 검증 오류(BusinessException)는 업로드를 남겨 둔다. 100MB 이상을 다시 올리지 않고
            // 올바른 latest.yml 로 완료만 다시 시도할 수 있다. 방치된 업로드는 1시간 뒤 정리된다.
        }
    }

    public void abort(String uploadId) {
        if (uploadId != null && UPLOAD_ID.matcher(uploadId).matches()) discard(uploadId);
    }

    // ---------------------------------------------------------------------------------------------

    /** 설치 파일을 먼저, 업데이트 정보를 나중에 게시한다. 앱은 정보 파일을 보고 받으러 오기 때문이다. */
    private void publish(Path uploaded, ReleaseManifest.Parsed manifest, long size) throws IOException {
        Files.createDirectories(downloadsDir);
        Path installerTmp = downloadsDir.resolve(INSTALLER_NAME + ".uploading");
        Files.copy(uploaded, installerTmp, StandardCopyOption.REPLACE_EXISTING);
        moveReplacing(installerTmp, downloadsDir.resolve(INSTALLER_NAME));

        Path manifestTmp = downloadsDir.resolve(MANIFEST_NAME + ".uploading");
        Files.writeString(manifestTmp,
                ReleaseManifest.render(manifest.version(), size, manifest.sha512(), INSTALLER_NAME, Instant.now()),
                StandardCharsets.UTF_8);
        moveReplacing(manifestTmp, downloadsDir.resolve(MANIFEST_NAME));
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Windows 실행 파일(PE)의 머리글 "MZ" 인지만 확인한다. 엉뚱한 파일이 게시되는 실수를 막는 최소한의 검사다. */
    private static void requireWindowsExecutable(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            if (in.read() != 'M' || in.read() != 'Z') {
                throw bad("Windows 설치 파일(.exe)이 아닙니다.");
            }
        }
    }

    private static String sha512Base64(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 최대 limit+1 바이트까지만 복사한다. 반환값이 limit 보다 크면 상한 초과다. */
    private static long copyLimited(InputStream in, OutputStream out, int limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while (total <= limit && (read = in.read(buffer, 0, (int) Math.min(buffer.length, limit + 1L - total))) != -1) {
            out.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private Upload find(String uploadId) {
        // UUID 형식만 받는다. 임시 파일 이름이 이 값에서 나오므로 경로 문자가 섞이면 안 된다.
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) throw new BusinessException(ErrorCode.NOT_FOUND);
        Upload upload = uploads.get(uploadId);
        if (upload == null) throw new BusinessException(ErrorCode.NOT_FOUND, "업로드 정보를 찾을 수 없습니다. 처음부터 다시 올려 주세요.");
        return upload;
    }

    private void discard(String uploadId) {
        Upload upload = uploads.remove(uploadId);
        if (upload != null) {
            try {
                Files.deleteIfExists(upload.file);
            } catch (IOException e) {
                log.warn("임시 업로드 파일을 지우지 못했습니다", e);
            }
        }
    }

    /** 오래된 업로드를 정리하고 동시에 진행하는 개수를 제한해 디스크가 차는 것을 막는다. */
    private void cleanup() {
        Instant limit = Instant.now().minus(STALE_AFTER);
        uploads.entrySet().removeIf(e -> {
            if (e.getValue().createdAt.isBefore(limit)) {
                try {
                    Files.deleteIfExists(e.getValue().file);
                } catch (IOException ignored) {
                    // 다음 정리 때 다시 시도한다
                }
                return true;
            }
            return false;
        });
        while (uploads.size() >= MAX_ACTIVE_UPLOADS) {
            uploads.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().seq))
                    .ifPresent(e -> discard(e.getKey()));
        }
    }

    private void requireEnabled() {
        if (downloadsDir == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "앱 배포 폴더가 설정되지 않았습니다. 서버의 DOWNLOADS_DIR 환경변수를 확인하세요.");
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private static BusinessException internal(String message, Exception cause) {
        log.error(message, cause);
        return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, message);
    }
}
