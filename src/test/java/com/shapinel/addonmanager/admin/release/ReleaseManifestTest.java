package com.shapinel.addonmanager.admin.release;

import com.shapinel.addonmanager.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업데이트 정보(latest.yml) 정책
 * - 버전·파일 이름·SHA-512·크기가 모두 올바른 경우에만 받아들인다.
 * - 서버가 만든 정보는 다시 읽어도 같은 값이 나오고, 버전 비교는 숫자 순서로 한다.
 */
class ReleaseManifestTest {

    private static final String SHA = "A".repeat(86) + "==";
    private static final String NAME = "AddonManager-Setup.exe";

    private static String yml(String version, String url, String sha, String size) {
        return "version: " + version + "\nfiles:\n  - url: " + url + "\n    sha512: " + sha + "\n    size: " + size
                + "\npath: " + url + "\nsha512: " + sha + "\nreleaseDate: '2026-01-01T00:00:00Z'\n";
    }

    /** electron-builder 가 실제로 만드는 형태: files 항목에 size 가 없다. */
    private static String builderYml(String version) {
        return "version: " + version + "\nfiles:\n  - url: " + NAME + "\n    sha512: " + SHA
                + "\npath: " + NAME + "\nsha512: " + SHA + "\nreleaseDate: '2026-11-24T23:09:35.817Z'\n";
    }

    private static void assertRejected(String text) {
        assertThatThrownBy(() -> ReleaseManifest.parse(text, NAME, 1000)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 올바른_정보를_읽는다() {
        ReleaseManifest.Parsed p = ReleaseManifest.parse(yml("0.2.0", NAME, SHA, "500"), NAME, 1000);

        assertThat(p.version()).isEqualTo("0.2.0");
        assertThat(p.declaredSize()).isEqualTo(500);
        assertThat(p.sha512()).isEqualTo(SHA);
    }

    @Test
    void 빌드_도구가_만든_size_없는_정보도_읽는다() {
        ReleaseManifest.Parsed p = ReleaseManifest.parse(builderYml("0.1.0"), NAME, 1000);

        assertThat(p.version()).isEqualTo("0.1.0");
        assertThat(p.declaredSize()).isNull();
        assertThat(p.sha512()).isEqualTo(SHA);
    }

    @Test
    void 버전_형식이_이상하면_거부한다() {
        assertRejected(yml("1.0", NAME, SHA, "500"));
        assertRejected(yml("v1.0.0", NAME, SHA, "500"));
        assertRejected(yml("1.0.0; rm -rf", NAME, SHA, "500"));
    }

    @Test
    void 다른_파일_이름이나_경로를_가리키면_거부한다() {
        assertRejected(yml("0.2.0", "other.exe", SHA, "500"));
        assertRejected(yml("0.2.0", "../AddonManager-Setup.exe", SHA, "500"));
        assertRejected(yml("0.2.0", "https://evil.example/a.exe", SHA, "500"));
    }

    @Test
    void sha512_형식이_틀리면_거부한다() {
        assertRejected(yml("0.2.0", NAME, "short", "500"));
        assertRejected(yml("0.2.0", NAME, "!".repeat(86) + "==", "500"));
    }

    @Test
    void 크기가_적혀_있는데_범위를_벗어나면_거부한다() {
        assertRejected(yml("0.2.0", NAME, SHA, "0"));
        assertRejected(yml("0.2.0", NAME, SHA, "-1"));
        assertRejected(yml("0.2.0", NAME, SHA, "1001"));
        assertRejected(yml("0.2.0", NAME, SHA, "abc"));
    }

    @Test
    void YAML_이_아니거나_위험한_태그는_거부한다() {
        assertRejected("");
        assertRejected("[1, 2");
        assertRejected("just text");
        assertRejected("version: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://x\"]]]]");
    }

    @Test
    void 서버가_만든_정보는_다시_읽어도_같다() {
        String text = ReleaseManifest.render("1.2.3", 4242, SHA, NAME, Instant.parse("2026-05-01T00:00:00Z"));

        ReleaseManifest.Parsed parsed = ReleaseManifest.parse(text, NAME, 100_000);
        ReleaseManifest.Published published = ReleaseManifest.readPublished(text);

        assertThat(parsed.version()).isEqualTo("1.2.3");
        assertThat(parsed.declaredSize()).isEqualTo(4242);
        assertThat(published.version()).isEqualTo("1.2.3");
        assertThat(published.size()).isEqualTo(4242);
        assertThat(published.releaseDate()).contains("2026-05-01");
    }

    @Test
    void 버전은_숫자_순서로_비교한다() {
        assertThat(ReleaseManifest.compareVersions("0.10.0", "0.9.0")).isPositive();
        assertThat(ReleaseManifest.compareVersions("1.0.0", "0.99.99")).isPositive();
        assertThat(ReleaseManifest.compareVersions("0.2.0", "0.2.0")).isZero();
        assertThat(ReleaseManifest.compareVersions("0.1.0", "0.2.0")).isNegative();
        assertThat(ReleaseManifest.compareVersions("1.0.0-beta", "1.0.0")).isNegative();
    }

    @Test
    void 읽을_수_없는_게시_정보는_null_이다() {
        assertThat(ReleaseManifest.readPublished("[깨진")).isNull();
        assertThat(ReleaseManifest.readPublished("foo: bar")).isNull();
    }
}
