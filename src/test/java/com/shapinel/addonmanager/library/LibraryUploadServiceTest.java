package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.library.LibraryLayout.FileSpec;
import com.shapinel.addonmanager.library.LibraryUploadService.UploadFile;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.BeginResponse;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.ChunkResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 자료실 올리기(파일 단위, 이어 올리기) 정책
 * - 시작할 때 보낸 파일 목록을 검사한다: 경로 조작·Windows 특수 이름·실행 파일(허용 확장자만)·같은 경로 중복(대소문자 무시)·
 *   크기 상한·해시 형식·.toc 없음을 거부한다.
 * - 이전 버전에 같은 파일(경로·크기·해시)이 있으면 다시 올리지 않고 그대로 가져온다(바뀐 파일만 올린다).
 * - 파일 조각은 지금까지 받은 위치에 이어서만 받고, 목록에 없는 경로·선언보다 큰 파일·해시가 다른 파일은 버린다.
 * - 모든 파일이 올라와야 끝낼 수 있고, 끝내면 내용 해시(서버·앱 공통 규칙)와 함께 자료실에 반영한다.
 * - 올리기 세션은 만든 관리자만 이어갈 수 있다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LibraryUploadServiceTest {

    private static final long ADMIN = 1L;

    @TempDir
    Path tmp;

    @Mock
    LibraryStore store;

    Path dir;
    LibraryUploadService service;

    @BeforeEach
    void setUp() {
        dir = tmp.resolve("library");
        service = new LibraryUploadService(dir, store, new LibraryService(dir, store));
        when(store.findByName(anyString())).thenReturn(Optional.empty());
        when(store.upsert(anyString(), anyString(), any(), anyString(), any(), anyLong(), any(), any())).thenAnswer(i ->
                LibraryAddonEntity.builder().publicId(i.getArgument(0)).name(i.getArgument(1)).version(i.getArgument(2))
                        .contentHash(i.getArgument(3)).requires(i.getArgument(4)).fileSize(i.getArgument(5)).build());
    }

    private static String sha(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static UploadFile file(String path, String content) {
        return new UploadFile(path, bytes(content).length, sha(bytes(content)));
    }

    private static UploadFile fileOf(String path, byte[] content) {
        return new UploadFile(path, content.length, sha(content));
    }

    private BeginResponse begin(UploadFile... files) {
        return service.begin(ADMIN, "Details", null, Arrays.asList(files));
    }

    private ChunkResponse put(String uploadId, String path, long offset, String content) {
        return service.chunk(ADMIN, uploadId, path, offset, new ByteArrayInputStream(bytes(content)));
    }

    private static final String TOC = "## Version: 1.2.3\n## Dependencies: LibA, ../evil\n";

    // ---------------------------------------------------------------------------------------------- 시작: 목록 검사

    @Test
    void 새_애드온은_모든_파일을_올려야_하고_빈_파일은_올릴_필요가_없다() {
        BeginResponse r = begin(file("Details.toc", TOC), file("Details.lua", "code"), file("empty.lua", ""));

        assertThat(r.getNeeded()).containsExactlyInAnyOrder("Details.toc", "Details.lua");
        assertThat(dir.resolve(".uploads").resolve(r.getUploadId()).resolve("data/empty.lua")).exists();
    }

    @Test
    void 경로_조작과_특수_이름은_거부한다() throws IOException {
        for (String bad : List.of("../evil.lua", "a/../../evil.lua", "/abs.lua", "C:/win.lua", "libs//x.lua", "CON.lua", "a/NUL", "trailing.", "bad<name>.lua")) {
            assertThatThrownBy(() -> begin(file("Details.toc", TOC), file(bad, "x")))
                    .as(bad).isInstanceOf(BusinessException.class);
        }
        // 거부된 올리기는 임시 폴더를 남기지 않는다(검사에서 걸러지면 아예 만들지 않는다)
        Path staging = dir.resolve(".uploads");
        assertThat(Files.notExists(staging) || Files.list(staging).findAny().isEmpty()).isTrue();
    }

    @Test
    void 실행_파일_형식은_거부하고_설정_파일은_허용한다() {
        assertThatThrownBy(() -> begin(file("Details.toc", TOC), file("run.exe", "MZ")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("허용하지 않는 파일 형식");
        assertThatThrownBy(() -> begin(file("Details.toc", TOC), file("install.bat", "x"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> begin(file("Details.toc", TOC), file("libs/x.dll", "x"))).isInstanceOf(BusinessException.class);

        assertThat(begin(file("Details.toc", TOC), file("libs/stylua.toml", "a = 1"), file("LICENSE", "MIT")).getNeeded()).hasSize(3);
    }

    @Test
    void 같은_경로_중복과_toc_없음과_잘못된_해시와_큰_파일은_거부한다() {
        assertThatThrownBy(() -> begin(file("Details.toc", TOC), file("A.lua", "1"), file("a.LUA", "2")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("두 번");
        assertThatThrownBy(() -> begin(file("Details.lua", "x")))
                .isInstanceOf(BusinessException.class).hasMessageContaining(".toc");
        assertThatThrownBy(() -> begin(file("Details.toc", TOC), new UploadFile("a.lua", 1, "xyz")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("해시");
        assertThatThrownBy(() -> begin(file("Details.toc", TOC), new UploadFile("big.lua", LibraryUploadService.MAX_FILE_BYTES + 1, "a".repeat(64))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("너무 큽니다");
        assertThatThrownBy(() -> service.begin(ADMIN, "../Evil", null, List.of(file("Evil.toc", TOC))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 빈_파일의_해시가_다르면_거부한다() {
        assertThatThrownBy(() -> begin(file("Details.toc", TOC), new UploadFile("empty.lua", 0, "a".repeat(64))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("빈 파일");
    }

    // ---------------------------------------------------------------------------------------------- 조각 올리기

    @Test
    void 조각은_받은_위치에_이어서만_받고_다_받으면_해시를_확인한다() {
        BeginResponse r = begin(file("Details.toc", TOC), file("Details.lua", "abcdef"));

        assertThat(put(r.getUploadId(), "Details.lua", 0, "abc").getReceived()).isEqualTo(3);
        assertThatThrownBy(() -> put(r.getUploadId(), "Details.lua", 0, "abc"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("다음에 올릴 위치는 3");
        ChunkResponse last = put(r.getUploadId(), "Details.lua", 3, "def");

        assertThat(last.isDone()).isTrue();
        assertThat(last.getReceived()).isEqualTo(6);
    }

    @Test
    void 해시가_다른_파일은_버리고_다시_올리게_한다() throws IOException {
        BeginResponse r = begin(file("Details.toc", TOC), file("Details.lua", "abcdef"));

        assertThatThrownBy(() -> put(r.getUploadId(), "Details.lua", 0, "abcxyz"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("달라졌습니다");
        assertThat(dir.resolve(".uploads").resolve(r.getUploadId()).resolve("data/Details.lua")).doesNotExist();
        assertThat(put(r.getUploadId(), "Details.lua", 0, "abcdef").isDone()).isTrue();      // 처음부터 다시 올리면 된다
    }

    @Test
    void 선언보다_큰_파일과_목록에_없는_경로와_경로_조작은_받지_않는다() {
        BeginResponse r = begin(file("Details.toc", TOC), file("Details.lua", "abc"));

        assertThatThrownBy(() -> put(r.getUploadId(), "Details.lua", 0, "abcdef")).isInstanceOf(BusinessException.class).hasMessageContaining("선언한 크기");
        assertThatThrownBy(() -> put(r.getUploadId(), "Other.lua", 0, "x")).isInstanceOf(BusinessException.class).hasMessageContaining("목록에 없는");
        assertThatThrownBy(() -> put(r.getUploadId(), "../escape.lua", 0, "x")).isInstanceOf(BusinessException.class);
        assertThat(dir.resolve("escape.lua")).doesNotExist();
    }

    @Test
    void 다른_관리자는_올리기를_이어갈_수_없고_존재도_알리지_않는다() {
        BeginResponse r = begin(file("Details.toc", TOC));

        assertThatThrownBy(() -> service.chunk(2L, r.getUploadId(), "Details.toc", 0, new ByteArrayInputStream(bytes(TOC))))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> service.complete(2L, r.getUploadId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.complete(ADMIN, "../x")).isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------------------------------------- 작은 파일 묶음

    /** [경로 길이 2][경로][크기 8][내용] 형식의 묶음 */
    private static byte[] pack(String... pairs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        for (int i = 0; i < pairs.length; i += 2) {
            byte[] path = bytes(pairs[i]);
            byte[] data = bytes(pairs[i + 1]);
            d.writeShort(path.length);
            d.write(path);
            d.writeLong(data.length);
            d.write(data);
        }
        return out.toByteArray();
    }

    private int putBundle(String uploadId, byte[] bundle) {
        return service.bundle(ADMIN, uploadId, new ByteArrayInputStream(bundle));
    }

    @Test
    void 작은_파일_여러_개를_한_번에_올린다() throws IOException {
        BeginResponse r = begin(file("Details.toc", TOC), file("a.lua", "aaa"), file("libs/b.lua", "bbbb"));

        assertThat(putBundle(r.getUploadId(), pack("Details.toc", TOC, "a.lua", "aaa", "libs/b.lua", "bbbb"))).isEqualTo(3);

        assertThat(Files.readString(dir.resolve(".uploads").resolve(r.getUploadId()).resolve("data/libs/b.lua"))).isEqualTo("bbbb");
        assertThat(service.complete(ADMIN, r.getUploadId()).getName()).isEqualTo("Details");   // 묶음만으로 끝낼 수 있다
    }

    @Test
    void 같은_묶음을_다시_보내도_이미_받은_파일은_건너뛴다() throws IOException {
        BeginResponse r = begin(file("Details.toc", TOC), file("a.lua", "aaa"));
        byte[] bundle = pack("Details.toc", TOC, "a.lua", "aaa");

        assertThat(putBundle(r.getUploadId(), bundle)).isEqualTo(2);
        assertThat(putBundle(r.getUploadId(), bundle)).isEqualTo(0);
    }

    @Test
    void 묶음_안의_해시나_크기가_다른_파일과_목록에_없는_경로는_거부한다() throws IOException {
        BeginResponse r = begin(file("Details.toc", TOC), file("a.lua", "aaa"));

        assertThatThrownBy(() -> putBundle(r.getUploadId(), pack("a.lua", "xxx"))).isInstanceOf(BusinessException.class).hasMessageContaining("달라졌습니다");
        assertThatThrownBy(() -> putBundle(r.getUploadId(), pack("a.lua", "aaaa"))).isInstanceOf(BusinessException.class).hasMessageContaining("크기");
        assertThatThrownBy(() -> putBundle(r.getUploadId(), pack("other.lua", "x"))).isInstanceOf(BusinessException.class).hasMessageContaining("목록에 없는");
        assertThatThrownBy(() -> putBundle(r.getUploadId(), pack("../escape.lua", "x"))).isInstanceOf(BusinessException.class);
        assertThat(dir.resolve("escape.lua")).doesNotExist();
    }

    @Test
    void 깨졌거나_잘린_묶음과_너무_큰_묶음은_거부한다() throws IOException {
        BeginResponse r = begin(file("Details.toc", TOC));
        byte[] good = pack("Details.toc", TOC);

        assertThatThrownBy(() -> putBundle(r.getUploadId(), java.util.Arrays.copyOf(good, good.length - 3))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> putBundle(r.getUploadId(), new byte[]{0, 0, 0})).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> putBundle(r.getUploadId(), new byte[LibraryUploadService.MAX_BUNDLE_BYTES + 1]))
                .isInstanceOf(BusinessException.class).hasMessageContaining("너무 큽니다");
    }

    @Test
    void 다른_관리자는_묶음을_올릴_수_없다() throws IOException {
        BeginResponse r = begin(file("Details.toc", TOC));

        assertThatThrownBy(() -> service.bundle(2L, r.getUploadId(), new ByteArrayInputStream(pack("Details.toc", TOC))))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ---------------------------------------------------------------------------------------------- 끝내기

    @Test
    void 모든_파일이_올라와야_끝낼_수_있다() {
        BeginResponse r = begin(file("Details.toc", TOC), file("Details.lua", "code"));
        put(r.getUploadId(), "Details.toc", 0, TOC);

        assertThatThrownBy(() -> service.complete(ADMIN, r.getUploadId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("아직 올라오지 않은 파일이 1개");
        verify(store, never()).upsert(any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void 끝내면_파일_목록과_내용_해시와_함께_자료실에_반영한다() throws IOException {
        BeginResponse r = begin(file("Details.toc", TOC), file("libs/a.lua", "code"), file("empty.lua", ""));
        put(r.getUploadId(), "Details.toc", 0, TOC);
        put(r.getUploadId(), "libs/a.lua", 0, "code");

        var done = service.complete(ADMIN, r.getUploadId());

        // 내용 해시 = 경로순 "폴더/경로\0해시\n" 의 SHA-256(서버·앱 공통 규칙)
        String expected = sha(bytes("Details/Details.toc\0" + sha(bytes(TOC)) + "\n"
                + "Details/empty.lua\0" + sha(bytes("")) + "\n"
                + "Details/libs/a.lua\0" + sha(bytes("code")) + "\n"));
        assertThat(done.getContentHash()).isEqualTo(expected);
        assertThat(done.getVersion()).isEqualTo("1.2.3");
        assertThat(done.getRequires()).containsExactly("LibA");            // 위험한 이름(../evil)은 제외
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        verify(store).upsert(id.capture(), eq("Details"), eq("1.2.3"), eq(expected), eq("LibA"), eq((long) (TOC.length() + 4)), any(), eq(ADMIN));
        Path item = dir.resolve(id.getValue());
        assertThat(Files.readString(item.resolve("data/libs/a.lua"))).isEqualTo("code");
        assertThat(LibraryLayout.readManifest(item)).extracting(FileSpec::path).containsExactly("Details.toc", "empty.lua", "libs/a.lua");
        assertThat(dir.resolve(".uploads").resolve(r.getUploadId())).doesNotExist();     // 임시 폴더는 옮겨졌다
    }

    @Test
    void 다시_올리면_바뀐_파일만_올리고_나머지는_이전_버전에서_가져온다() throws IOException {
        BeginResponse first = begin(file("Details.toc", TOC), file("same.lua", "unchanged"), file("changed.lua", "old"));
        put(first.getUploadId(), "Details.toc", 0, TOC);
        put(first.getUploadId(), "same.lua", 0, "unchanged");
        put(first.getUploadId(), "changed.lua", 0, "old");
        service.complete(ADMIN, first.getUploadId());
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        verify(store).upsert(id.capture(), any(), any(), any(), any(), anyLong(), any(), any());
        when(store.findByName("Details")).thenReturn(Optional.of(LibraryAddonEntity.builder().publicId(id.getValue()).name("Details").build()));

        BeginResponse second = begin(file("Details.toc", TOC), file("same.lua", "unchanged"), file("changed.lua", "NEW"), file("added.lua", "more"));

        assertThat(second.getNeeded()).containsExactlyInAnyOrder("changed.lua", "added.lua");
        put(second.getUploadId(), "changed.lua", 0, "NEW");
        put(second.getUploadId(), "added.lua", 0, "more");
        service.complete(ADMIN, second.getUploadId());
        Path item = dir.resolve(id.getValue());
        assertThat(Files.readString(item.resolve("data/same.lua"))).isEqualTo("unchanged");
        assertThat(Files.readString(item.resolve("data/changed.lua"))).isEqualTo("NEW");
        assertThat(dir.resolve(".trash")).isEmptyDirectory();                 // 교체된 옛 버전은 치워졌다
    }

    @Test
    void 취소하면_임시_파일을_지운다() {
        BeginResponse r = begin(file("Details.toc", TOC));
        Path staging = dir.resolve(".uploads").resolve(r.getUploadId());
        assertThat(staging).exists();

        service.abort(ADMIN, r.getUploadId());

        assertThat(staging).doesNotExist();
        assertThatThrownBy(() -> service.complete(ADMIN, r.getUploadId())).isInstanceOf(BusinessException.class);
    }

    @Test
    void 저장_폴더가_설정되지_않으면_기능이_꺼진다() {
        LibraryUploadService disabled = new LibraryUploadService((Path) null, store, new LibraryService((Path) null, store));

        assertThatThrownBy(() -> disabled.begin(ADMIN, "Details", null, List.of(file("Details.toc", TOC))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("LIBRARY_DIR");
    }

    @Test
    void 서버가_다시_시작되면_올리다_만_임시_파일은_버린다() throws IOException {
        BeginResponse r = begin(file("Details.toc", TOC));
        assertThat(dir.resolve(".uploads").resolve(r.getUploadId())).exists();

        new LibraryUploadService(dir, store, new LibraryService(dir, store));     // 재시작

        assertThat(dir.resolve(".uploads")).doesNotExist();
    }

    @Test
    void 파일_하나가_여러_조각이어도_합쳐서_해시를_맞춘다() {
        byte[] big = new byte[3 * 1024];
        Arrays.fill(big, (byte) 'z');
        BeginResponse r = begin(file("Details.toc", TOC), fileOf("data.lua", big));
        List<byte[]> parts = new ArrayList<>(List.of(Arrays.copyOfRange(big, 0, 1024), Arrays.copyOfRange(big, 1024, 2048), Arrays.copyOfRange(big, 2048, 3072)));
        long offset = 0;
        ChunkResponse last = null;
        for (byte[] part : parts) {
            last = service.chunk(ADMIN, r.getUploadId(), "data.lua", offset, new ByteArrayInputStream(part));
            offset += part.length;
        }

        assertThat(last.isDone()).isTrue();
    }
}
