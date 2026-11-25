package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 자료실 zip 검사 정책
 * - 이 zip 은 받은 사람들의 게임 폴더에 풀리므로 폴더 밖으로 나가는 경로(zip slip), Windows 에서 다른 의미를 갖는 이름,
 *   실행 파일·스크립트(허용한 확장자만 통과), 지나친 크기·항목 수, 중복 경로, 최상위 파일, .toc 없는 구성은 거부한다.
 * - 자료실 항목은 폴더 하나다. 폴더가 여럿이면 거부한다.
 * - 정상 애드온은 폴더 이름, .toc 의 버전·필요한 애드온(Dependencies/RequiredDeps), 내용 해시를 읽는다.
 * - 내용 해시는 압축 방식·항목 순서와 무관하게 내용이 같으면 같고, 내용이나 경로가 다르면 달라진다(관리자 앱과 같은 규칙).
 */
class AddonZipInspectorTest {

    @TempDir
    Path tmp;

    private Path zip(Map<String, String> files) throws IOException {
        return zip(files, Deflater.DEFAULT_COMPRESSION);
    }

    private Path zip(Map<String, String> files, int level) throws IOException {
        Path path = tmp.resolve("t" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.setLevel(level);
            for (Map.Entry<String, String> e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return path;
    }

    private static Map<String, String> files(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    private void assertRejected(Map<String, String> files) throws IOException {
        Path z = zip(files);
        assertThatThrownBy(() -> AddonZipInspector.inspect(z))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------- 정상

    @Test
    void 정상_애드온은_폴더와_버전을_읽는다() throws IOException {
        Path z = zip(files(
                "Details/Details.toc", "## Interface: 11508\n## Title: Details\n## Version: 1.2.3\nDetails.lua\n",
                "Details/Details.lua", "print('hi')"));

        AddonZipInspector.Inspection r = AddonZipInspector.inspect(z);

        assertThat(r.folder()).isEqualTo("Details");
        assertThat(r.version()).isEqualTo("1.2.3");
        assertThat(r.requires()).isEmpty();
        assertThat(r.fileCount()).isEqualTo(2);
        assertThat(r.contentHash()).hasSize(64).matches("^[0-9a-f]+$");
    }

    @Test
    void 껍데기_toc_와_클라이언트별_toc_가_나뉜_애드온도_읽는다() throws IOException {
        // Questie 처럼 Questie.toc 는 안내용이고 실제 정보가 Questie_Forever.toc 에 있다
        Path z = zip(files(
                "Questie/Questie.toc", "## Interface: 00000\n## Title: not supported\n",
                "Questie/Questie_Forever.toc", "## Version: 12.14.0\n## Dependencies: QuestieDB\n",
                "Questie/Questie_Vanilla.toc", "## Version: 12.14.0\n## RequiredDeps: QuestieDB, QuestieTrace\n",
                "Questie/Modules/a.lua", "x"));

        AddonZipInspector.Inspection r = AddonZipInspector.inspect(z);

        assertThat(r.version()).isEqualTo("12.14.0");
        assertThat(r.requires()).containsExactly("QuestieDB", "QuestieTrace");    // 모든 toc 의 합집합, 중복 제거
    }

    @Test
    void 확장자가_없거나_점으로_시작하는_파일과_대문자_확장자와_문서_형식을_허용한다() throws IOException {
        Path z = zip(files("A/A.toc", "## Version: 1\n", "A/LICENSE", "mit", "A/.pkgmeta", "x", "A/Icon.TGA", "x",
                "A/Main.LUA", "x", "A/Libs/LDB/README.textile", "x", "A/b.blp", "x", "A/c.ttf", "x", "A/d.ogg", "x"));

        assertThat(AddonZipInspector.inspect(z).fileCount()).isEqualTo(9);
    }

    @Test
    void 아주_큰_toc_도_앞부분의_메타데이터를_읽어_통과한다() throws IOException {
        // 실제 애드온(QuestieDB)에는 18MB 짜리 .toc 가 있다. 크다고 거부하면 정상 애드온을 올릴 수 없다.
        Path path = tmp.resolve("bigtoc.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("Big/Big_Forever.toc"));
            out.write("## Version: 9.9.9\n## Dependencies: Lib\n".getBytes(StandardCharsets.UTF_8));
            byte[] line = "src/data/some/file.lua\n".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 150_000; i++) out.write(line);       // 약 3.4MB
            out.closeEntry();
        }

        AddonZipInspector.Inspection r = AddonZipInspector.inspect(path);

        assertThat(r.version()).isEqualTo("9.9.9");
        assertThat(r.requires()).containsExactly("Lib");
    }

    @Test
    void 버전이_없으면_null_이다() throws IOException {
        assertThat(AddonZipInspector.inspect(zip(files("A/A.toc", "## Title: x\n"))).version()).isNull();
    }

    // ---------------------------------------------------------------- 내용 해시

    @Test
    void 내용_해시는_정해진_규칙으로_계산된다() throws Exception {
        // 규칙: 경로순으로 "경로\0파일SHA-256\n" 을 이은 것의 SHA-256. 관리자 앱(JS)도 같은 규칙이어야 한다.
        Path z = zip(files("A/b.lua", "world", "A/A.toc", "## Version: 1\n", "A/a/c.lua", "hello"));
        Map<String, String> sorted = new TreeMap<>(Map.of("A/b.lua", "world", "A/A.toc", "## Version: 1\n", "A/a/c.lua", "hello"));
        StringBuilder lines = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) lines.append(e.getKey()).append('\0').append(sha256(e.getValue())).append('\n');

        assertThat(AddonZipInspector.inspect(z).contentHash()).isEqualTo(sha256(lines.toString()));
    }

    @Test
    void 내용_해시는_압축_방식과_항목_순서와_무관하다() throws IOException {
        Path a = zip(files("A/A.toc", "## Version: 1\n", "A/x.lua", "1", "A/y.lua", "2"), Deflater.NO_COMPRESSION);
        Path b = zip(files("A/y.lua", "2", "A/x.lua", "1", "A/A.toc", "## Version: 1\n"), Deflater.BEST_COMPRESSION);

        assertThat(AddonZipInspector.inspect(a).contentHash()).isEqualTo(AddonZipInspector.inspect(b).contentHash());
    }

    @Test
    void 내용이나_경로가_다르면_해시도_다르다() throws IOException {
        String base = AddonZipInspector.inspect(zip(files("A/A.toc", "## Version: 1\n", "A/x.lua", "1"))).contentHash();

        assertThat(AddonZipInspector.inspect(zip(files("A/A.toc", "## Version: 1\n", "A/x.lua", "2"))).contentHash()).isNotEqualTo(base);
        assertThat(AddonZipInspector.inspect(zip(files("A/A.toc", "## Version: 1\n", "A/z.lua", "1"))).contentHash()).isNotEqualTo(base);
        assertThat(AddonZipInspector.inspect(zip(files("A/A.toc", "## Version: 1\n", "A/x.lua", "1", "A/extra.lua", "0"))).contentHash()).isNotEqualTo(base);
    }

    // ---------------------------------------------------------------- 폴더 구성

    @Test
    void 폴더가_둘_이상이면_거부한다() throws IOException {
        assertRejected(files("Questie/Questie.toc", "## Version: 1\n", "QuestieDB/QuestieDB.toc", "## Version: 1\n"));
    }

    @Test
    void 최상위_폴더의_대소문자만_다른_것은_같은_폴더로_본다() throws IOException {
        Path z = zip(files("Addon/Addon.toc", "## Version: 1\n", "ADDON/a.lua", "x"));

        AddonZipInspector.Inspection r = AddonZipInspector.inspect(z);

        assertThat(r.folder()).isEqualTo("Addon");
        assertThat(r.fileCount()).isEqualTo(2);
    }

    @Test
    void 같은_경로가_두_번_들어_있으면_거부한다() throws IOException {
        // 어느 쪽이 풀릴지 모호하고 검사를 피하는 데 쓸 수 있다. 대소문자만 다른 경로도 같은 경로다.
        assertRejected(files("A/A.toc", "## Version: 1\n", "A/x.lua", "1", "A/X.LUA", "2"));
    }

    @Test
    void 최상위에_파일이_있으면_거부한다() throws IOException {
        assertRejected(files("A/A.toc", "x", "README.md", "x"));
    }

    @Test
    void toc_가_하나도_없으면_거부한다() throws IOException {
        assertRejected(files("A/main.lua", "x"));
    }

    @Test
    void 빈_zip_과_zip_이_아닌_파일은_거부한다() throws IOException {
        assertRejected(files());
        Path notZip = tmp.resolve("x.zip");
        Files.writeString(notZip, "이건 zip 이 아닙니다");
        assertThatThrownBy(() -> AddonZipInspector.inspect(notZip)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 폴더_이름이_너무_길면_거부한다() throws IOException {
        String longName = "A".repeat(AddonZipInspector.MAX_NAME + 1);
        assertRejected(files(longName + "/" + longName + ".toc", "## Version: 1\n"));
    }

    // ---------------------------------------------------------------- 경로 조작

    @Test
    void 폴더_밖으로_나가는_경로를_거부한다() throws IOException {
        assertRejected(files("A/A.toc", "x", "A/../../evil.lua", "x"));
        assertRejected(files("A/A.toc", "x", "../evil.lua", "x"));
        assertRejected(files("A/A.toc", "x", "A/sub/../../../evil.lua", "x"));
    }

    @Test
    void 절대_경로와_드라이브_경로를_거부한다() throws IOException {
        assertRejected(files("A/A.toc", "x", "/etc/passwd", "x"));
        assertRejected(files("A/A.toc", "x", "C:/Windows/evil.lua", "x"));
        assertRejected(files("A/A.toc", "x", "C:\\Windows\\evil.lua", "x"));
    }

    @Test
    void 역슬래시로_숨긴_상위_경로도_거부한다() throws IOException {
        assertRejected(files("A/A.toc", "x", "A\\..\\..\\evil.lua", "x"));
    }

    @Test
    void Windows_에서_다른_의미를_갖는_이름을_거부한다() throws IOException {
        assertRejected(files("A/A.toc", "x", "A/con.lua", "x"));
        assertRejected(files("A/A.toc", "x", "A/NUL", "x"));
        assertRejected(files("A/A.toc", "x", "A/file.lua:stream", "x"));
        assertRejected(files("A/A.toc", "x", "A/name.", "x"));
        assertRejected(files("A/A.toc", "x", "A/name ", "x"));
        assertRejected(files("A/A.toc", "x", "A/we?rd.lua", "x"));
        assertRejected(files("A/A.toc", "x", "A//b.lua", "x"));
        assertRejected(files("A/A.toc", "x", "A/" + "a".repeat(300) + ".lua", "x"));
    }

    // ---------------------------------------------------------------- 실행 파일

    @Test
    void 실행_파일과_스크립트_확장자를_거부한다() throws IOException {
        for (String bad : new String[]{"evil.exe", "evil.dll", "evil.bat", "evil.cmd", "evil.ps1", "evil.js", "evil.vbs",
                "evil.msi", "evil.jar", "evil.lnk", "evil.sh", "evil.py", "evil.EXE", "evil.scr", "evil.hta", "evil.so", "image.png.exe"}) {
            assertRejected(files("A/A.toc", "## Version: 1\n", "A/" + bad, "x"));
        }
    }

    // ---------------------------------------------------------------- 크기·개수

    @Test
    void 항목이_너무_많으면_거부한다() throws IOException {
        Path path = tmp.resolve("many.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("A/A.toc"));
            out.write("## Version: 1\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (int i = 0; i <= AddonZipInspector.MAX_ENTRIES; i++) {
                out.putNextEntry(new ZipEntry("A/f" + i + ".lua"));
                out.closeEntry();
            }
        }

        assertThatThrownBy(() -> AddonZipInspector.inspect(path)).hasMessageContaining("너무 많습니다");
    }

    @Test
    void 압축을_풀면_너무_큰_zip_을_거부한다() throws IOException {
        // 0 으로 채운 데이터는 거의 압축되어 파일은 작지만 풀면 500MB 를 넘는다(압축 폭탄)
        Path path = tmp.resolve("bomb.zip");
        byte[] chunk = new byte[1024 * 1024];
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("A/A.toc"));
            out.write("## Version: 1\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("A/big.lua"));
            writeZeros(out, chunk, 520);
            out.closeEntry();
        }

        assertThat(Files.size(path)).isLessThan(5 * 1024 * 1024);
        assertThatThrownBy(() -> AddonZipInspector.inspect(path)).hasMessageContaining("너무 큽니다");
    }

    private static void writeZeros(OutputStream out, byte[] chunk, int megabytes) throws IOException {
        for (int i = 0; i < megabytes; i++) out.write(chunk);
    }

    // ---------------------------------------------------------------- .toc 해석

    @Test
    void toc_해석은_BOM_과_공백과_키_대소문자를_견딘다() {
        AddonZipInspector.Toc toc = AddonZipInspector.parseToc("\uFEFF## VERSION :  3.4.5  \r\n## dependencies:  A ,B,  ,C \r\n");

        assertThat(toc.version()).isEqualTo("3.4.5");
        assertThat(toc.requires()).containsExactly("A", "B", "C");
    }

    @Test
    void 선택_의존성은_받지_않고_위험한_이름은_버린다() {
        AddonZipInspector.Toc toc = AddonZipInspector.parseToc(
                "## OptionalDeps: Opt\n## Dependencies: Good, ../evil, a/b, c\\d, C:x, ok2\n");

        assertThat(toc.requires()).containsExactly("Good", "ok2");
    }

    @Test
    void toc_판별은_폴더_이름_뒤에_구분자가_올_때만_인정한다() {
        assertThat(AddonZipInspector.isTocFileFor("Questie", "Questie.toc")).isTrue();
        assertThat(AddonZipInspector.isTocFileFor("Questie", "Questie_Vanilla.toc")).isTrue();
        assertThat(AddonZipInspector.isTocFileFor("Questie", "Questie-Classic.toc")).isTrue();
        assertThat(AddonZipInspector.isTocFileFor("Questie", "QuestieTrace.toc")).isFalse();
        assertThat(AddonZipInspector.isTocFileFor("Questie", "Questie.lua")).isFalse();
        assertThat(AddonZipInspector.isTocFileFor("questie", "QUESTIE_FOREVER.TOC")).isTrue();
    }
}
