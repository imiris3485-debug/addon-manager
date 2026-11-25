package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.LibraryAddonResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 자료실 정책
 * - 검사를 통과한 zip 만 서버 폴더에 UUID 이름으로 저장한다(클라이언트가 준 이름·경로는 파일 이름에 쓰지 않는다).
 *   자료실에서의 이름은 요청값이 아니라 zip 의 최상위 폴더에서 가져온다.
 * - 검사에 실패하거나 크기를 넘는 업로드는 파일을 남기지 않고 DB 에도 기록하지 않는다.
 * - 같은 폴더를 다시 올리면 같은 ID 에 새 파일로 바꾼다(업데이트).
 * - 경로가 섞인 ID 로는 받거나 지울 수 없다. 저장 폴더가 설정되지 않으면 기능이 꺼진다.
 */
@ExtendWith(MockitoExtension.class)
class LibraryServiceTest {

    private static final long ADMIN = 1L;
    private static final String UUID_A = "1a2b3c4d-2222-3333-4444-55555555abcd";

    @TempDir
    Path tmp;

    @Mock
    LibraryStore store;

    Path dir;
    LibraryService service;

    @BeforeEach
    void setUp() {
        dir = tmp.resolve("library");
        service = new LibraryService(dir, store);
    }

    private static byte[] zip(String... pairs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < pairs.length; i += 2) {
                out.putNextEntry(new ZipEntry(pairs[i]));
                out.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] validZip() throws IOException {
        return zip("Details/Details.toc", "## Version: 1.2.3\n## Dependencies: Lib\n", "Details/Details.lua", "x");
    }

    private static LibraryAddonEntity entity(String publicId, String name) {
        return LibraryAddonEntity.builder().publicId(publicId).name(name).version("1.2.3").contentHash("a".repeat(64))
                .requires("Lib,Other").fileSize(100).note("메모").uploadedBy(ADMIN).build();
    }

    private long filesInDir() throws IOException {
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }

    private static void assertCode(Throwable t, ErrorCode code) {
        assertThat(t).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    // ------------------------------------------------------------------ 업로드

    @Test
    void 검사를_통과한_zip_을_UUID_이름으로_저장하고_이름은_zip_의_폴더에서_가져온다() throws IOException {
        byte[] data = validZip();
        when(store.findByName("Details")).thenReturn(Optional.empty());
        when(store.upsert(any(), eq("Details"), eq("1.2.3"), any(), eq("Lib"), eq((long) data.length), eq("메모"), eq(ADMIN)))
                .thenReturn(entity(UUID_A, "Details"));

        LibraryAddonResponse result = service.upload(ADMIN, "메모", new ByteArrayInputStream(data));

        assertThat(result.getName()).isEqualTo("Details");
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(store).upsert(id.capture(), eq("Details"), eq("1.2.3"), hash.capture(), eq("Lib"), eq((long) data.length), eq("메모"), eq(ADMIN));
        assertThat(id.getValue()).matches("^[0-9a-f-]{36}$");
        assertThat(Files.readAllBytes(dir.resolve(id.getValue() + ".zip"))).isEqualTo(data);
        assertThat(hash.getValue()).hasSize(64).matches("^[0-9a-f]+$");
        assertThat(filesInDir()).isEqualTo(1);                       // 임시 파일이 남지 않는다
    }

    @Test
    void 같은_폴더를_다시_올리면_같은_ID_에_새_파일로_바꾼다() throws IOException {
        byte[] data = validZip();
        when(store.findByName("Details")).thenReturn(Optional.of(entity(UUID_A, "Details")));
        when(store.upsert(eq(UUID_A), any(), any(), any(), any(), anyLong(), any(), any())).thenReturn(entity(UUID_A, "Details"));
        Files.createDirectories(dir);
        Files.write(dir.resolve(UUID_A + ".zip"), new byte[]{1, 2, 3});

        service.upload(ADMIN, null, new ByteArrayInputStream(data));

        assertThat(Files.readAllBytes(dir.resolve(UUID_A + ".zip"))).isEqualTo(data);
        assertThat(filesInDir()).isEqualTo(1);
    }

    @Test
    void 실행_파일이_든_zip_은_거부하고_파일도_기록도_남기지_않는다() throws IOException {
        byte[] bad = zip("Details/Details.toc", "## Version: 1\n", "Details/evil.exe", "MZ");

        assertThatThrownBy(() -> service.upload(ADMIN, null, new ByteArrayInputStream(bad)))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));

        assertThat(filesInDir()).isZero();
        verify(store, never()).upsert(any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void 경로_조작이_든_zip_은_거부한다() throws IOException {
        byte[] bad = zip("Details/Details.toc", "## Version: 1\n", "Details/../../escape.lua", "x");

        assertThatThrownBy(() -> service.upload(ADMIN, null, new ByteArrayInputStream(bad)))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
        assertThat(filesInDir()).isZero();
    }

    @Test
    void 폴더가_둘_이상인_zip_은_거부한다() throws IOException {
        byte[] bad = zip("A/A.toc", "## Version: 1\n", "B/B.toc", "## Version: 1\n");

        assertThatThrownBy(() -> service.upload(ADMIN, null, new ByteArrayInputStream(bad)))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
        assertThat(filesInDir()).isZero();
    }

    @Test
    void zip_이_아닌_파일과_빈_본문은_거부한다() throws IOException {
        assertThatThrownBy(() -> service.upload(ADMIN, null, new ByteArrayInputStream("not a zip".getBytes(StandardCharsets.UTF_8))))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.upload(ADMIN, null, new ByteArrayInputStream(new byte[0])))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
        assertThat(filesInDir()).isZero();
    }

    @Test
    void 크기_상한을_넘으면_거부하고_임시_파일을_지운다() throws IOException {
        // 상한(90MB)보다 1바이트 큰 스트림. 메모리에 올리지 않고 0 을 흘려 보낸다.
        InputStream tooBig = new InputStream() {
            long remaining = LibraryService.MAX_ZIP_BYTES + 1;

            @Override
            public int read() {
                return remaining-- > 0 ? 0 : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (remaining <= 0) return -1;
                int n = (int) Math.min(len, remaining);
                remaining -= n;
                return n;
            }
        };

        assertThatThrownBy(() -> service.upload(ADMIN, null, tooBig)).satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));

        assertThat(filesInDir()).isZero();
    }

    @Test
    void 메모가_너무_길거나_제어문자가_있으면_거부한다() throws IOException {
        byte[] data = validZip();
        assertThatThrownBy(() -> service.upload(ADMIN, "m".repeat(501), new ByteArrayInputStream(data)))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.upload(ADMIN, "bad\u0000note", new ByteArrayInputStream(data)))
                .satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
        assertThat(filesInDir()).isZero();
    }

    @Test
    void 메타데이터_저장에_실패하면_새로_둔_파일을_남기지_않는다() throws IOException {
        byte[] data = validZip();
        when(store.findByName("Details")).thenReturn(Optional.empty());
        when(store.upsert(any(), any(), any(), any(), any(), anyLong(), any(), any())).thenThrow(new IllegalStateException("DB 오류"));
        when(store.findByPublicId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(ADMIN, null, new ByteArrayInputStream(data))).isInstanceOf(IllegalStateException.class);

        assertThat(filesInDir()).isZero();
    }

    @Test
    void 저장_폴더가_설정되지_않으면_기능이_꺼진다() {
        LibraryService disabled = new LibraryService("", store);

        assertThatThrownBy(() -> disabled.upload(ADMIN, null, new ByteArrayInputStream(new byte[]{1})))
                .satisfies(t -> assertCode(t, ErrorCode.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> disabled.download(UUID_A)).satisfies(t -> assertCode(t, ErrorCode.INTERNAL_SERVER_ERROR));
    }

    // ------------------------------------------------------------------ 받기

    @Test
    void 목록의_ID_로_저장된_파일을_받는다() throws IOException {
        when(store.findByPublicId(UUID_A)).thenReturn(Optional.of(entity(UUID_A, "Details")));
        Files.createDirectories(dir);
        Files.write(dir.resolve(UUID_A + ".zip"), validZip());

        Resource file = service.download(UUID_A);

        assertThat(file.getFile()).isEqualTo(dir.resolve(UUID_A + ".zip").toFile());
    }

    @Test
    void 경로가_섞인_ID_로는_받을_수_없다() {
        for (String id : new String[]{"../../etc/passwd", "..\\x", UUID_A + "/../x", "x", "", UUID_A.toUpperCase(), UUID_A + ".zip"}) {
            assertThatThrownBy(() -> service.download(id)).as("ID '%s'", id).satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));
        }
        verify(store, never()).findByPublicId(any());
    }

    @Test
    void 목록에_없는_ID_나_파일이_사라진_항목은_찾을_수_없다() {
        when(store.findByPublicId(UUID_A)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.download(UUID_A)).satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));

        String other = "9e9e9e9e-2222-3333-4444-55555555abcd";
        when(store.findByPublicId(other)).thenReturn(Optional.of(entity(other, "Ghost")));
        assertThatThrownBy(() -> service.download(other)).satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));      // 파일 없음
    }

    // ------------------------------------------------------------------ 삭제·목록

    @Test
    void 삭제는_기록과_파일을_모두_지운다() throws IOException {
        when(store.findByPublicId(UUID_A)).thenReturn(Optional.of(entity(UUID_A, "Details")));
        Files.createDirectories(dir);
        Files.write(dir.resolve(UUID_A + ".zip"), validZip());

        service.delete(UUID_A, ADMIN);

        verify(store).delete(UUID_A);
        assertThat(dir.resolve(UUID_A + ".zip")).doesNotExist();
    }

    @Test
    void 없는_항목이나_경로가_섞인_ID_는_삭제하지_못한다() {
        when(store.findByPublicId(UUID_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(UUID_A, ADMIN)).satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.delete("../../x", ADMIN)).satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));
        verify(store, never()).delete(any());
    }

    @Test
    void 목록은_필요한_애드온_문자열을_나눠서_준다() {
        when(store.findAll()).thenReturn(List.of(entity(UUID_A, "Details")));

        List<LibraryAddonResponse> list = service.list();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getRequires()).containsExactly("Lib", "Other");
        assertThat(list.get(0).getId()).isEqualTo(UUID_A);
        assertThat(list.get(0).getContentHash()).hasSize(64);
    }

    @Test
    void 필요한_애드온이_없는_항목은_빈_목록이다() {
        LibraryAddonEntity none = LibraryAddonEntity.builder().publicId(UUID_A).name("A").contentHash("a".repeat(64))
                .fileSize(1).uploadedBy(ADMIN).build();
        when(store.findAll()).thenReturn(List.of(none));

        assertThat(service.list().get(0).getRequires()).isEmpty();
    }

    // ------------------------------------------------------------------ 관리자가 지정하는 같이 설치할 애드온

    @Test
    void 사용자가_받는_같이_설치할_애드온은_toc_것과_관리자_지정의_합집합이다() {
        LibraryAddonEntity e = LibraryAddonEntity.builder().publicId(UUID_A).name("Questie").contentHash("a".repeat(64))
                .requires("Lib,QuestieDB").extraRequires("QuestieDB,QuestieTrace,Questie").fileSize(1).uploadedBy(ADMIN).build();
        when(store.findAll()).thenReturn(List.of(e));

        LibraryAddonResponse r = service.list().get(0);

        assertThat(r.getRequires()).containsExactly("Lib", "QuestieDB", "QuestieTrace");   // 중복 제거, 자기 자신(Questie) 제외
        assertThat(r.getExtraRequires()).containsExactly("QuestieDB", "QuestieTrace", "Questie");
    }

    @Test
    void 관리자가_같이_설치할_애드온을_지정한다() {
        when(store.updateExtraRequires(eq(UUID_A), eq("QuestieDB,QuestieTrace"))).thenReturn(Optional.of(
                LibraryAddonEntity.builder().publicId(UUID_A).name("Questie").contentHash("a".repeat(64))
                        .extraRequires("QuestieDB,QuestieTrace").fileSize(1).uploadedBy(ADMIN).build()));

        LibraryAddonResponse r = service.setExtraRequires(UUID_A, List.of(" QuestieDB ", "QuestieTrace", "QuestieDB"));

        assertThat(r.getRequires()).containsExactly("QuestieDB", "QuestieTrace");
    }

    @Test
    void 지정을_비우면_지운다() {
        when(store.updateExtraRequires(eq(UUID_A), eq((String) null))).thenReturn(Optional.of(entity(UUID_A, "Details")));

        service.setExtraRequires(UUID_A, List.of());

        verify(store).updateExtraRequires(UUID_A, null);
    }

    @Test
    void 경로_문자가_섞인_이름이나_너무_많은_지정은_거부한다() {
        // 진짜 역슬래시("a\\b")와 이름 한가운데의 제어문자("a\u0001b")를 함께 확인한다
        for (String bad : new String[]{"../evil", "a/b", "a\\b", "a\u0001b", "C:x", "", "  ", "a,b", "x".repeat(101)}) {
            assertThatThrownBy(() -> service.setExtraRequires(UUID_A, List.of(bad)))
                    .as("이름 '%s'", bad).satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
        }
        List<String> tooMany = java.util.stream.IntStream.range(0, 31).mapToObj(i -> "A" + i).toList();
        assertThatThrownBy(() -> service.setExtraRequires(UUID_A, tooMany)).satisfies(t -> assertCode(t, ErrorCode.BAD_REQUEST));
        verify(store, never()).updateExtraRequires(any(), any());
    }

    @Test
    void 없는_항목이나_경로가_섞인_ID_에는_지정할_수_없다() {
        when(store.updateExtraRequires(eq(UUID_A), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setExtraRequires(UUID_A, List.of("A"))).satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.setExtraRequires("../../x", List.of("A"))).satisfies(t -> assertCode(t, ErrorCode.NOT_FOUND));
    }
}
