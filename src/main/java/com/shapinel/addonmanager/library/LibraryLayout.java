package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.exception.BusinessException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * 자료실 저장 구조. 애드온 하나 = 서버 폴더 `LIBRARY_DIR/<publicId>/` 아래에
 * <pre>
 *   data/<상대 경로>   애드온의 파일들(폴더 안 구조 그대로)
 *   manifest.txt        파일 목록(경로·크기·SHA-256)
 * </pre>
 * zip 한 덩어리가 아니라 파일 단위로 두기 때문에 크기 제한 없이 올릴 수 있고(파일별로 나눠 올림), 바뀐 파일만 올리고 받을 수 있다.
 *
 * <p>내용 해시(contentHash)는 기존과 같은 규칙이다: "폴더/경로\0파일SHA-256\n" 을 경로순(UTF-16)으로 이은 것의 SHA-256.
 * 관리자 앱·사용자 앱(frontend/src/lib/hash.ts)도 같은 규칙이라, 규칙을 바꾸면 양쪽을 함께 바꾼다.
 */
final class LibraryLayout {

    static final String DATA = "data";
    static final String MANIFEST = "manifest.txt";
    /** 올리는 중인 파일을 두는 곳(완료하면 자료실 폴더로 옮긴다). 서버가 다시 시작되면 남은 것은 버린다. */
    static final String STAGING = ".uploads";
    /** 교체된 옛 버전을 잠시 두는 곳 */
    static final String TRASH = ".trash";

    private static final String MANIFEST_HEADER = "v1";

    private LibraryLayout() {
    }

    /** 파일 하나: 폴더 기준 상대 경로(/ 구분), 크기, SHA-256(소문자 16진수) */
    record FileSpec(String path, long size, String sha256) {
    }

    static Path itemDir(Path root, String publicId) {
        return root.resolve(publicId);
    }

    static Path dataDir(Path itemDir) {
        return itemDir.resolve(DATA);
    }

    /**
     * 상대 경로를 data 폴더 안의 실제 경로로 바꾼다. 경로는 이미 검사했지만(AddonZipInspector.validatePath),
     * 파일을 만들거나 내려주기 직전에 한 번 더 폴더 밖으로 나가지 않는지 확인한다(방어를 겹친다).
     */
    static Path resolveInside(Path dataDir, String relPath) {
        Path base = dataDir.toAbsolutePath().normalize();
        Path target = base.resolve(relPath).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "허용되지 않는 경로입니다.");
        }
        return target;
    }

    /** 경로순으로 "폴더/경로\0파일해시\n" 을 이은 것의 SHA-256. */
    static String contentHash(String folder, Collection<FileSpec> files) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (FileSpec f : files) sorted.put(folder + "/" + f.path(), f.sha256());
        StringBuilder lines = new StringBuilder();
        for (var e : sorted.entrySet()) lines.append(e.getKey()).append('\0').append(e.getValue()).append('\n');
        return HexFormat.of().formatHex(newDigest().digest(lines.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** 빈 파일의 SHA-256 */
    static String emptySha256() {
        return HexFormat.of().formatHex(newDigest().digest(new byte[0]));
    }

    /** 메모리에 있는 내용의 SHA-256 */
    static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(newDigest().digest(data));
    }

    static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) digest.update(buf, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 파일 목록을 `해시<TAB>크기<TAB>경로` 한 줄씩 쓴다. 경로에는 제어문자(탭 포함)를 허용하지 않으므로(validatePath) 구분이 모호하지 않다.
     * 별도 JSON 라이브러리 없이 읽고 쓸 수 있는 단순한 형식을 쓴다.
     */
    static void writeManifest(Path itemDir, Collection<FileSpec> files) throws IOException {
        StringBuilder sb = new StringBuilder(MANIFEST_HEADER).append('\n');
        for (FileSpec f : files) sb.append(f.sha256()).append('\t').append(f.size()).append('\t').append(f.path()).append('\n');
        Files.writeString(itemDir.resolve(MANIFEST), sb.toString(), StandardCharsets.UTF_8);
    }

    static List<FileSpec> readManifest(Path itemDir) throws IOException {
        List<String> lines = Files.readAllLines(itemDir.resolve(MANIFEST), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !MANIFEST_HEADER.equals(lines.get(0))) throw new IOException("지원하지 않는 파일 목록 형식입니다.");
        List<FileSpec> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", 3);
            if (p.length != 3) throw new IOException("파일 목록이 깨져 있습니다.");
            out.add(new FileSpec(p[2], Long.parseLong(p[1]), p[0]));
        }
        return out;
    }

    /** 폴더를 통째로 지운다. 심볼릭 링크는 따라가지 않고 링크 자체만 지운다. 이미 없으면 넘어간다. */
    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
