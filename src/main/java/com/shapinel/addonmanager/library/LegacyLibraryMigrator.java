package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.library.LibraryLayout.FileSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 예전에는 애드온 하나를 zip 한 덩어리(`<id>.zip`)로 저장했다. 지금은 파일 단위(`<id>/data/…` + 목록)라서, 서버가 시작될 때
 * 남아 있는 zip 을 한 번 새 형식으로 바꾼다(관리자가 다시 동기화하지 않아도 되게). 바꾼 뒤 zip 은 지운다.
 *
 * <p>보안: zip 안의 경로도 올리기와 같은 검사(경로 조작·특수 이름·허용 확장자)를 다시 통과해야 하고,
 * 바꾼 내용의 해시가 DB 에 기록된 해시와 다르면 바꾸지 않는다.
 */
@Slf4j
@Component
public class LegacyLibraryMigrator {

    private final Path root;       // null 이면 기능 비활성
    private final LibraryStore store;

    @Autowired
    public LegacyLibraryMigrator(@Value("${app.library-dir:}") String dir, LibraryStore store) {
        this.root = dir.isBlank() ? null : Path.of(dir);
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        if (root == null) return;
        for (LibraryAddonEntity item : store.findAll()) {
            Path zip = root.resolve(item.getPublicId() + ".zip");
            if (!Files.isRegularFile(zip)) continue;
            Path itemDir = LibraryLayout.itemDir(root, item.getPublicId());
            try {
                if (Files.exists(itemDir.resolve(LibraryLayout.MANIFEST))) {
                    Files.deleteIfExists(zip);       // 이미 새 형식이 있다
                } else {
                    convert(item, zip, itemDir);
                    Files.deleteIfExists(zip);
                    log.info("자료실 항목을 파일 단위 저장으로 바꿨습니다: {}", item.getName());
                }
            } catch (IOException | RuntimeException e) {
                log.error("자료실 항목을 새 형식으로 바꾸지 못했습니다(관리자가 다시 동기화해야 합니다): {}", item.getName(), e);
            }
        }
    }

    private void convert(LibraryAddonEntity item, Path zip, Path itemDir) throws IOException {
        Path stage = root.resolve(LibraryLayout.STAGING).resolve("migrate-" + UUID.randomUUID());
        Path data = LibraryLayout.dataDir(stage);
        Files.createDirectories(data);
        try {
            List<FileSpec> specs = new ArrayList<>();
            try (ZipFile zf = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry entry = en.nextElement();
                    if (entry.isDirectory()) continue;
                    String[] parts = AddonZipInspector.validatePath(entry.getName());
                    if (parts.length < 2 || !parts[0].equalsIgnoreCase(item.getName())) throw new IOException("폴더 구조가 올바르지 않습니다.");
                    String rel = String.join("/", java.util.Arrays.copyOfRange(parts, 1, parts.length));
                    AddonZipInspector.checkExtension(rel, parts[parts.length - 1]);

                    Path target = LibraryLayout.resolveInside(data, rel);
                    Files.createDirectories(target.getParent());
                    MessageDigest digest = LibraryLayout.newDigest();
                    long size;
                    try (InputStream in = new DigestInputStream(zf.getInputStream(entry), digest)) {
                        size = Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    specs.add(new FileSpec(rel, size, HexFormat.of().formatHex(digest.digest())));
                }
            }
            specs.sort(java.util.Comparator.comparing(FileSpec::path));
            String hash = LibraryLayout.contentHash(item.getName(), specs);
            if (!hash.equals(item.getContentHash().toLowerCase(Locale.ROOT))) throw new IOException("내용 해시가 기록과 다릅니다.");
            LibraryLayout.writeManifest(stage, specs);
            try {
                Files.move(stage, itemDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(stage, itemDir);
            }
        } finally {
            LibraryLayout.deleteTree(stage);
        }
    }
}
