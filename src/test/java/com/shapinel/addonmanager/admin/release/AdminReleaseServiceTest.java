package com.shapinel.addonmanager.admin.release;

import com.shapinel.addonmanager.admin.release.domain.response.AdminReleaseResponses.ReleaseResponse;
import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 데스크톱 앱 배포 정책
 * - 설치 파일은 조각으로 순서대로 받고, 서버가 SHA-512·크기를 latest.yml 과 직접 대조한 뒤에만 게시한다.
 * - 게시되는 파일 이름은 서버가 정한 상수뿐이고, 저장되는 latest.yml 은 서버가 검증한 값으로 새로 만든다.
 * - 낮은 버전, Windows 실행 파일이 아닌 파일, 순서가 어긋나거나 과도하게 큰 조각, 경로가 섞인 업로드 ID 는 거부한다.
 */
class AdminReleaseServiceTest {

    @TempDir
    Path tmp;

    Path downloads;
    AdminReleaseService service;

    @BeforeEach
    void setUp() {
        downloads = tmp.resolve("downloads");
        service = new AdminReleaseService(downloads, tmp.resolve("uploads"));
    }

    private static byte[] exe(int size, int seed) {
        byte[] data = new byte[size];
        Arrays.fill(data, (byte) seed);
        data[0] = 'M';
        data[1] = 'Z';
        return data;
    }

    private static String sha512(byte[] data) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(data));
    }

    private static String yml(String version, byte[] data, String url) throws Exception {
        return ReleaseManifest.render(version, data.length, sha512(data), url, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private String upload(byte[] data, int chunk) {
        String id = service.start().getUploadId();
        int index = 0;
        for (int off = 0; off < data.length; off += chunk) {
            service.appendChunk(id, index++, new ByteArrayInputStream(data, off, Math.min(chunk, data.length - off)));
        }
        return id;
    }

    private static void assertCode(Throwable t, ErrorCode code) {
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getErrorCode()).isEqualTo(code);
    }

    @Test
    void 조각을_순서대로_올리고_검증이_맞으면_게시한다() throws Exception {
        byte[] data = exe(300_000, 7);
        String id = upload(data, 100_000);

        ReleaseResponse result = service.complete(id, yml("0.2.0", data, "AddonManager-Setup.exe"), 1L);

        assertThat(result.isPublished()).isTrue();
        assertThat(result.getVersion()).isEqualTo("0.2.0");
        assertThat(Files.readAllBytes(downloads.resolve("AddonManager-Setup.exe"))).isEqualTo(data);
        String manifest = Files.readString(downloads.resolve("latest.yml"), StandardCharsets.UTF_8);
        assertThat(manifest).contains("version: 0.2.0").contains("path: AddonManager-Setup.exe").contains(sha512(data));
    }

    @Test
    void 빌드_도구가_만든_size_없는_latest_yml_로도_게시되고_크기는_서버가_채운다() throws Exception {
        byte[] data = exe(5000, 9);
        String builderYml = "version: 0.1.0\nfiles:\n  - url: AddonManager-Setup.exe\n    sha512: " + sha512(data)
                + "\npath: AddonManager-Setup.exe\nsha512: " + sha512(data) + "\nreleaseDate: '2026-11-24T23:09:35.817Z'\n";

        ReleaseResponse result = service.complete(upload(data, 2000), builderYml, 1L);

        assertThat(result.getVersion()).isEqualTo("0.1.0");
        assertThat(result.getSize()).isEqualTo(5000);
        assertThat(Files.readString(downloads.resolve("latest.yml"))).contains("size: 5000");
    }

    @Test
    void 아직_게시된_것이_없으면_빈_상태를_돌려준다() {
        assertThat(service.getCurrent().isPublished()).isFalse();
    }

    @Test
    void 파일이_latest_yml_의_sha512_와_다르면_게시하지_않는다() throws Exception {
        byte[] data = exe(1000, 1);
        byte[] other = exe(1000, 2);
        String id = upload(data, 1000);

        assertThatThrownBy(() -> service.complete(id, yml("0.2.0", other, "AddonManager-Setup.exe"), 1L))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
        assertThat(downloads.resolve("AddonManager-Setup.exe")).doesNotExist();
        assertThat(downloads.resolve("latest.yml")).doesNotExist();
    }

    @Test
    void 크기가_다르면_게시하지_않는다() throws Exception {
        byte[] data = exe(1000, 1);
        String id = upload(data, 1000);
        byte[] bigger = exe(2000, 1);

        assertThatThrownBy(() -> service.complete(id, yml("0.2.0", bigger, "AddonManager-Setup.exe"), 1L))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
    }

    @Test
    void 검증에_실패해도_업로드는_남아_올바른_정보로_다시_완료할_수_있다() throws Exception {
        byte[] data = exe(1000, 1);
        String id = upload(data, 1000);

        assertThatThrownBy(() -> service.complete(id, "version: 9", 1L)).isInstanceOf(BusinessException.class);
        ReleaseResponse result = service.complete(id, yml("0.2.0", data, "AddonManager-Setup.exe"), 1L);

        assertThat(result.getVersion()).isEqualTo("0.2.0");
    }

    @Test
    void 다른_파일_이름을_가리키는_latest_yml_은_거부한다() throws Exception {
        byte[] data = exe(1000, 1);
        String id = upload(data, 1000);

        assertThatThrownBy(() -> service.complete(id, yml("0.2.0", data, "evil.exe"), 1L))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.complete(id, yml("0.2.0", data, "../../etc/passwd"), 1L))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
    }

    @Test
    void Windows_실행_파일이_아니면_거부한다() throws Exception {
        byte[] data = "not an exe".getBytes(StandardCharsets.UTF_8);
        String id = upload(data, 100);

        assertThatThrownBy(() -> service.complete(id, yml("0.2.0", data, "AddonManager-Setup.exe"), 1L))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
    }

    @Test
    void 게시된_버전보다_낮은_버전은_올릴_수_없고_같거나_높으면_올릴_수_있다() throws Exception {
        byte[] first = exe(1000, 1);
        service.complete(upload(first, 1000), yml("0.2.0", first, "AddonManager-Setup.exe"), 1L);

        byte[] lower = exe(1000, 2);
        String lowerId = upload(lower, 1000);
        assertThatThrownBy(() -> service.complete(lowerId, yml("0.1.9", lower, "AddonManager-Setup.exe"), 1L))
                .satisfies(t -> assertCode(t, ErrorCode.CONFLICT));
        assertThat(service.getCurrent().getVersion()).isEqualTo("0.2.0");

        byte[] higher = exe(1000, 3);
        service.complete(upload(higher, 1000), yml("0.3.0", higher, "AddonManager-Setup.exe"), 1L);
        assertThat(service.getCurrent().getVersion()).isEqualTo("0.3.0");
        assertThat(Files.readAllBytes(downloads.resolve("AddonManager-Setup.exe"))).isEqualTo(higher);
    }

    @Test
    void 조각_순서가_어긋나면_거부한다() {
        String id = service.start().getUploadId();

        assertThatThrownBy(() -> service.appendChunk(id, 1, new ByteArrayInputStream(new byte[10])))
                .satisfies(t -> assertCode(t, ErrorCode.CONFLICT));
    }

    @Test
    void 조각_크기_상한을_넘으면_업로드를_폐기한다() {
        String id = service.start().getUploadId();
        byte[] tooBig = new byte[AdminReleaseService.CHUNK_SIZE + 1];

        assertThatThrownBy(() -> service.appendChunk(id, 0, new ByteArrayInputStream(tooBig)))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.appendChunk(id, 0, new ByteArrayInputStream(new byte[10])))
                .satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));
    }

    @Test
    void 경로가_섞인_업로드_ID_는_찾을_수_없다() {
        assertThatThrownBy(() -> service.appendChunk("../../etc/passwd", 0, new ByteArrayInputStream(new byte[1])))
                .satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.complete("..\\..\\x", "version: 1", 1L))
                .satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));
    }

    @Test
    void 배포_폴더가_설정되지_않으면_업로드를_시작할_수_없다() {
        AdminReleaseService disabled = new AdminReleaseService("");

        assertThatThrownBy(disabled::start).satisfies(t -> assertCode(t, ErrorCode.INTERNAL_SERVER_ERROR));
        assertThat(disabled.getCurrent().isPublished()).isFalse();
    }

    @Test
    void 동시에_진행하는_업로드는_개수를_제한한다() {
        String first = service.start().getUploadId();
        service.start();
        service.start();
        service.start();

        assertThatThrownBy(() -> service.appendChunk(first, 0, new ByteArrayInputStream(new byte[1])))
                .satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));
    }
}
