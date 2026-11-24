package com.shapinel.addonmanager.admin.release;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.exception.BusinessException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 데스크톱 앱 업데이트 정보(latest.yml) 검증과 생성.
 *
 * <p>업로드된 latest.yml 을 그대로 저장하지 않는다. 앱은 이 파일을 믿고 설치 파일을 내려받아 실행하므로,
 * 필요한 값(버전·크기·SHA-512)만 검증해 꺼낸 뒤 서버가 새로 만들어 저장한다.
 * 임의의 YAML 내용이나 다른 파일 이름을 가리키는 값이 들어갈 여지를 없애기 위해서다.
 */
final class ReleaseManifest {

    // 숫자 3개(+선택적 프리릴리스). 앱이 비교할 수 있는 형태만 받는다.
    private static final Pattern VERSION = Pattern.compile("^(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})(-[0-9A-Za-z.\\-]{1,32})?$");
    // SHA-512 의 base64(88자, 끝이 ==)
    private static final Pattern SHA512_BASE64 = Pattern.compile("^[A-Za-z0-9+/]{86}==$");

    private ReleaseManifest() {
    }

    /** declaredSize: electron-builder 가 만든 latest.yml 에는 size 가 없을 수 있어 null 일 수 있다. */
    record Parsed(String version, Long declaredSize, String sha512) {
    }

    static Parsed parse(String yml, String installerName, long maxBytes) {
        Map<?, ?> root = load(yml);

        String version = asString(root.get("version"));
        if (version == null || !VERSION.matcher(version).matches()) {
            throw bad("latest.yml 의 version 형식이 올바르지 않습니다. 예: 0.2.0");
        }

        // 설치 파일 이름은 서버가 정한 값만 허용한다. 다른 경로·파일을 가리키면 거부한다.
        Object path = root.get("path");
        if (path != null && !installerName.equals(asString(path))) {
            throw bad("latest.yml 의 path 가 " + installerName + " 이 아닙니다.");
        }
        if (!(root.get("files") instanceof List<?> files) || files.size() != 1 || !(files.get(0) instanceof Map<?, ?> file)) {
            throw bad("latest.yml 의 files 항목이 올바르지 않습니다.");
        }
        if (!installerName.equals(asString(file.get("url")))) {
            throw bad("latest.yml 의 files.url 이 " + installerName + " 이 아닙니다.");
        }

        String sha512 = asString(file.get("sha512"));
        if (sha512 == null || !SHA512_BASE64.matcher(sha512).matches()) {
            throw bad("latest.yml 의 sha512 형식이 올바르지 않습니다.");
        }
        // size 는 없어도 된다(빌드 도구가 생략한다). 있으면 범위를 확인하고, 서버가 실제 업로드 크기와 대조한다.
        Long declaredSize = null;
        if (file.get("size") != null) {
            if (!(file.get("size") instanceof Number number) || number.longValue() <= 0 || number.longValue() > maxBytes) {
                throw bad("latest.yml 의 size 가 올바르지 않습니다.");
            }
            declaredSize = number.longValue();
        }
        return new Parsed(version, declaredSize, sha512);
    }

    /** 서버가 검증을 마친 값으로 새로 만든 latest.yml. electron-updater(generic)가 읽는 형식이다. */
    static String render(String version, long size, String sha512, String installerName, Instant releaseDate) {
        return "version: " + version + "\n"
                + "files:\n"
                + "  - url: " + installerName + "\n"
                + "    sha512: " + sha512 + "\n"
                + "    size: " + size + "\n"
                + "path: " + installerName + "\n"
                + "sha512: " + sha512 + "\n"
                + "releaseDate: '" + releaseDate + "'\n";
    }

    /** 이미 게시된 latest.yml 에서 버전·크기·릴리스 시각만 읽는다. 읽을 수 없으면 null. */
    static Published readPublished(String yml) {
        try {
            Map<?, ?> root = load(yml);
            String version = asString(root.get("version"));
            Object date = root.get("releaseDate");
            long size = root.get("files") instanceof List<?> files && !files.isEmpty()
                    && files.get(0) instanceof Map<?, ?> f && f.get("size") instanceof Number n ? n.longValue() : 0L;
            return version == null ? null : new Published(version, size, date == null ? null : String.valueOf(date));
        } catch (RuntimeException e) {
            return null;
        }
    }

    record Published(String version, long size, String releaseDate) {
    }

    /** 숫자 3개를 순서대로 비교한다. 같으면 프리릴리스가 없는 쪽이 더 높다. */
    static int compareVersions(String a, String b) {
        var ma = VERSION.matcher(a);
        var mb = VERSION.matcher(b);
        if (!ma.matches() || !mb.matches()) {
            throw new IllegalArgumentException("버전 형식이 올바르지 않습니다.");
        }
        for (int i = 1; i <= 3; i++) {
            int cmp = Integer.compare(Integer.parseInt(ma.group(i)), Integer.parseInt(mb.group(i)));
            if (cmp != 0) return cmp;
        }
        String pa = ma.group(4);
        String pb = mb.group(4);
        if (pa == null && pb == null) return 0;
        if (pa == null) return 1;
        if (pb == null) return -1;
        return pa.compareTo(pb);
    }

    private static Map<?, ?> load(String yml) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(64 * 1024);          // 거대한 문서로 메모리를 쓰게 하는 것을 막는다
        options.setMaxAliasesForCollections(10);       // 별칭 폭탄 방지
        Object loaded;
        try {
            // SafeConstructor: 임의의 클래스를 만드는 YAML 태그를 허용하지 않는다
            loaded = new Yaml(new SafeConstructor(options)).load(yml);
        } catch (RuntimeException e) {
            throw bad("latest.yml 을 읽을 수 없습니다. 빌드가 만든 파일 그대로 올려 주세요.");
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw bad("latest.yml 형식이 올바르지 않습니다.");
        }
        return map;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
