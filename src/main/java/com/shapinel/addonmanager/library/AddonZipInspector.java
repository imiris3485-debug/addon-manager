package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.exception.BusinessException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 올라온 애드온 zip(폴더 하나)이 "게임 폴더에 풀어도 안전한 애드온"인지 검사하고 메타데이터를 읽는다.
 *
 * <p>보안: 이 zip 은 받은 사람들의 AddOns 폴더에 그대로 풀린다. 그래서 올리는 시점에 다음을 거부한다.
 * 폴더 밖으로 나가는 경로(zip slip), Windows 에서 다른 의미를 갖는 이름, 실행 파일·스크립트(허용한 확장자만 통과),
 * 지나치게 큰 압축 해제 크기·파일 수(압축 폭탄), 같은 경로의 중복 항목. 클라이언트(웹 앱·데스크톱 앱)도 같은 검사를 다시
 * 하지만, 서버가 먼저 걸러서 문제 있는 파일이 자료실에 올라가지 않게 한다.
 *
 * <p>내용 해시(contentHash)는 "파일 경로 + 파일별 SHA-256" 목록을 경로순으로 이은 것의 SHA-256 이다. zip 의 압축 방식·시각이
 * 달라도 내용이 같으면 같은 값이 나온다. 관리자 앱(frontend/src/lib/library.ts)도 같은 규칙으로 계산해서,
 * 바뀌지 않은 애드온은 다시 올리지 않고 사용자는 설치한 내용과 비교해 업데이트를 판단한다. 규칙을 바꾸면 양쪽을 함께 바꾼다.
 */
public final class AddonZipInspector {

    static final long MAX_UNCOMPRESSED_BYTES = 500L * 1024 * 1024;
    static final int MAX_ENTRIES = 20_000;
    static final int MAX_NAME = 100;
    static final int MAX_REQUIRES = 30;
    private static final int MAX_TOC_HEAD_BYTES = 1024 * 1024;
    private static final int MAX_PATH_LENGTH = 240;

    /**
     * 애드온에 들어 있는 것이 자연스러운 파일 형식. 이 밖의 확장자(exe, dll, bat, js 등)는 거부한다.
     * 실제 애드온(Questie 등)으로 확인해 보니 내장 라이브러리의 설명 문서(.textile)처럼 뜻밖의 문서 형식이 있어
     * 문서 계열은 넉넉하게 허용한다. 실행될 수 있는 형식(스크립트·실행 파일·라이브러리)은 허용하지 않는다.
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // 애드온 코드·데이터
            "lua", "xml", "toc", "json", "yml", "yaml", "cfg", "ini", "csv", "tsv",
            // 이미지·글꼴·소리
            "tga", "blp", "png", "jpg", "jpeg", "gif", "bmp", "dds", "svg", "webp", "ico",
            "ttf", "otf", "ogg", "mp3", "wav", "flac", "m4a",
            // 문서
            "txt", "text", "md", "markdown", "textile", "rst", "adoc", "rtf", "html", "htm", "css",
            "lic", "license", "diff", "patch");

    private static final Pattern FORBIDDEN_CHARS = Pattern.compile("[<>:\"|?*\\u0000-\\u001f]");
    private static final Pattern RESERVED_NAME = Pattern.compile("^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOC_META = Pattern.compile("^##\\s*([^:]+?)\\s*:\\s*(.*)$");

    private AddonZipInspector() {
    }

    /**
     * @param folder   zip 의 최상위 폴더 = 자료실에서의 이름
     * @param version  .toc 의 ## Version(파일 이름순으로 처음 나온 값)
     * @param requires .toc 의 Dependencies/RequiredDeps(같이 설치할 폴더)
     */
    record Inspection(String folder, String version, List<String> requires, String contentHash, int fileCount, long uncompressedBytes) {
    }

    static Inspection inspect(Path zipPath) {
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            return inspect(zip);
        } catch (ZipException e) {
            throw bad("올바른 zip 파일이 아닙니다.");
        } catch (IOException e) {
            throw bad("zip 파일을 읽을 수 없습니다.");
        }
    }

    private static Inspection inspect(ZipFile zip) throws IOException {
        String folder = null;
        // 정규화한 경로("폴더/하위/파일") → 항목. 경로순으로 정렬해 두고 해시를 계산한다.
        Map<String, ZipEntry> files = new TreeMap<>();
        Set<String> seenLower = new HashSet<>();
        List<ZipEntry> tocs = new ArrayList<>();
        long declared = 0;
        int entries = 0;

        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry entry = en.nextElement();
            if (++entries > MAX_ENTRIES) throw bad("zip 안의 항목이 너무 많습니다.");

            String[] parts = validatePath(entry.getName());
            if (parts.length == 1 && !entry.isDirectory()) {
                throw bad("zip 최상위에 파일이 있습니다(" + parts[0] + "). 애드온 폴더째로 묶어 주세요.");
            }
            // 자료실 항목은 폴더 하나다. 폴더가 여럿이면 하나씩 따로 올려야 사용자가 골라 설치할 수 있다.
            if (folder == null) {
                folder = parts[0];
                if (folder.length() > MAX_NAME) throw bad("폴더 이름이 너무 깁니다(최대 " + MAX_NAME + "자).");
            } else if (!folder.equalsIgnoreCase(parts[0])) {
                throw bad("zip 에 폴더가 둘 이상 있습니다(" + folder + ", " + parts[0] + "). 애드온 폴더 하나씩 올려 주세요.");
            }
            if (entry.isDirectory()) continue;

            checkExtension(entry.getName(), parts[parts.length - 1]);
            // 최상위 폴더의 대소문자 표기를 하나로 맞춘 경로. 같은 경로(대소문자 무시)가 둘이면 어느 것이 풀릴지 모호해 거부한다.
            String normalized = folder + "/" + String.join("/", java.util.Arrays.copyOfRange(parts, 1, parts.length));
            if (!seenLower.add(normalized.toLowerCase(Locale.ROOT))) throw bad("같은 경로가 두 번 들어 있습니다: " + shorten(normalized));
            files.put(normalized, entry);

            long size = entry.getSize();
            if (size < 0) throw bad("압축 해제 크기를 알 수 없는 항목이 있습니다.");
            declared += size;
            if (declared > MAX_UNCOMPRESSED_BYTES) throw bad("압축을 풀면 너무 큽니다(최대 500MB).");

            if (parts.length == 2 && isTocFileFor(folder, parts[1])) tocs.add(entry);
        }

        if (folder == null) throw bad("zip 에 애드온 폴더가 없습니다.");
        if (files.isEmpty()) throw bad("zip 에 파일이 없습니다.");
        if (tocs.isEmpty()) throw bad("애드온 폴더에 .toc 파일이 없습니다. 올바른 애드온 zip 이 아닙니다.");

        Toc toc = mergeTocs(zip, tocs);
        Hashed hashed = hashContents(zip, files);
        return new Inspection(folder, toc.version(), toc.requires(), hashed.hash(), files.size(), hashed.bytes());
    }

    private record Hashed(String hash, long bytes) {
    }

    /**
     * 파일 경로순으로 "경로\0파일SHA-256\n" 을 이어 붙인 것의 SHA-256. 실제로 읽은 바이트 수도 세어서,
     * zip 이 선언한 크기를 속여도(압축 폭탄) 상한을 넘으면 거부한다.
     */
    private static Hashed hashContents(ZipFile zip, Map<String, ZipEntry> files) throws IOException {
        StringBuilder lines = new StringBuilder();
        long total = 0;
        for (Map.Entry<String, ZipEntry> e : files.entrySet()) {
            MessageDigest fileDigest = newDigest();
            try (InputStream in = new DigestInputStream(zip.getInputStream(e.getValue()), fileDigest)) {
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) {
                    total += read;
                    if (total > MAX_UNCOMPRESSED_BYTES) throw bad("압축을 풀면 너무 큽니다(최대 500MB).");
                }
            }
            lines.append(e.getKey()).append('\0').append(HexFormat.of().formatHex(fileDigest.digest())).append('\n');
        }
        MessageDigest all = newDigest();
        return new Hashed(HexFormat.of().formatHex(all.digest(lines.toString().getBytes(StandardCharsets.UTF_8))), total);
    }

    /** 경로를 조각으로 나눠 검사한다. 위험하면 거부. */
    static String[] validatePath(String rawName) {
        String name = rawName.replace('\\', '/');
        if (name.isEmpty() || name.length() > MAX_PATH_LENGTH || name.startsWith("/")) {
            throw bad("허용되지 않는 경로가 있습니다: " + shorten(rawName));
        }
        String[] parts = name.endsWith("/") ? name.substring(0, name.length() - 1).split("/", -1) : name.split("/", -1);
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".") || p.equals("..")
                    || FORBIDDEN_CHARS.matcher(p).find() || RESERVED_NAME.matcher(p).matches()
                    // Windows 는 이름 끝의 점·공백을 지워서 다른 이름과 같은 파일이 된다
                    || p.endsWith(".") || p.endsWith(" ")) {
                throw bad("허용되지 않는 경로가 있습니다: " + shorten(rawName));
            }
        }
        return parts;
    }

    private static void checkExtension(String fullName, String fileName) {
        int dot = fileName.lastIndexOf('.');
        // 확장자가 없는 파일(LICENSE 등)과 점으로 시작하는 파일(.pkgmeta 등)은 허용한다
        if (dot <= 0) return;
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw bad("허용하지 않는 파일 형식이 있습니다: " + shorten(fullName) + " (.lua, .xml, .toc, 이미지, 글꼴, 소리, 문서만 올릴 수 있습니다)");
        }
    }

    /** `Name.toc`, `Name_Vanilla.toc`, `Name-Classic.toc` 처럼 폴더 이름으로 시작하고 구분자(_ 또는 -)가 이어지는 .toc. */
    static boolean isTocFileFor(String folder, String fileName) {
        String f = fileName.toLowerCase(Locale.ROOT);
        String n = folder.toLowerCase(Locale.ROOT);
        if (!f.endsWith(".toc") || !f.startsWith(n)) return false;
        String rest = f.substring(n.length(), f.length() - 4);
        return rest.isEmpty() || rest.startsWith("_") || rest.startsWith("-");
    }

    record Toc(String version, List<String> requires) {
    }

    /** 폴더의 여러 .toc 에서 파일 이름순으로 처음 나온 버전과, 모든 .toc 가 요구하는 폴더의 합집합. */
    private static Toc mergeTocs(ZipFile zip, List<ZipEntry> tocs) throws IOException {
        List<ZipEntry> sorted = new ArrayList<>(tocs);
        sorted.sort(Comparator.comparing(e -> baseName(e.getName()).toLowerCase(Locale.ROOT)));
        String version = null;
        Set<String> requires = new LinkedHashSet<>();
        for (ZipEntry entry : sorted) {
            Toc toc = parseToc(readHead(zip, entry));
            if (version == null && toc.version() != null) version = toc.version();
            requires.addAll(toc.requires());
        }
        List<String> list = new ArrayList<>(requires);
        return new Toc(version, list.size() > MAX_REQUIRES ? list.subList(0, MAX_REQUIRES) : list);
    }

    /**
     * .toc 의 메타데이터 줄(## 키: 값)에서 버전과 필수 애드온을 읽는다.
     * 필수 애드온은 WoW 표준인 `## Dependencies:` 와 `## RequiredDeps:`(쉼표 구분)다. 선택(OptionalDeps)은 받지 않는다.
     */
    static Toc parseToc(String text) {
        String version = null;
        Set<String> requires = new LinkedHashSet<>();
        for (String raw : text.replace("﻿", "").split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.startsWith("##")) continue;
            Matcher m = TOC_META.matcher(line);
            if (!m.matches()) continue;
            String key = m.group(1).toLowerCase(Locale.ROOT);
            String value = m.group(2).trim();
            if (key.equals("version") && !value.isEmpty() && version == null) {
                version = value.length() > 100 ? value.substring(0, 100) : value;
            }
            if (key.equals("dependencies") || key.equals("requireddeps")) {
                for (String dep : value.split(",")) {
                    String name = dep.trim();
                    if (isSafeFolderName(name)) requires.add(name);
                }
            }
        }
        return new Toc(version, new ArrayList<>(requires));
    }

    /** 다른 폴더를 가리키는 이름이므로 경로 문자가 섞이면 안 된다. */
    public static boolean isSafeFolderName(String name) {
        return !name.isEmpty() && name.length() <= MAX_NAME && !name.equals(".") && !name.equals("..")
                && !FORBIDDEN_CHARS.matcher(name).find() && !name.contains("/") && !name.contains("\\") && !name.contains(",");
    }

    /**
     * .toc 의 앞부분만 읽는다. 버전·의존성 같은 메타데이터(## 줄)는 파일 위쪽에 있고, 실제 애드온 중에는
     * 18MB 짜리 .toc 도 있다(QuestieDB). 크다고 거부하면 정상 애드온을 올릴 수 없으므로 앞쪽 일부만 읽는다.
     */
    private static String readHead(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readNBytes(MAX_TOC_HEAD_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static String shorten(String s) {
        String clean = s.replaceAll("[\\u0000-\\u001f]", "?");
        return clean.length() > 80 ? clean.substring(0, 77) + "..." : clean;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
