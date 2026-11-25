package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.library.LibraryLayout.FileSpec;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.FileResponse;
import com.shapinel.addonmanager.library.domain.response.LibraryResponses.LibraryAddonResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 자료실(내려받기·지우기) 정책
 * - 받는 쪽에는 "파일 목록(경로·크기·SHA-256)"과, 목록에 있는 파일만 한 스트림(묶음)에 담아 내려준다(파일마다 요청하면 수천 개짜리 애드온은 너무 느리다).
 *   목록에 없는 경로나 폴더 밖으로 나가는 경로가 하나라도 있으면 거부한다.
 * - 경로가 섞인 ID 로는 받거나 지울 수 없고, 저장 폴더가 설정되지 않으면 기능이 꺼진다.
 * - 지우면 DB 와 서버의 파일(옛 zip 포함)을 모두 지운다.
 * - 같이 설치할 애드온 지정은 폴더 이름만 받는다(경로 문자 거부).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LibraryServiceTest {

    private static final String ID = "1a2b3c4d-2222-3333-4444-55555555abcd";

    @TempDir
    Path tmp;

    @Mock
    LibraryStore store;

    Path dir;
    LibraryService service;

    @BeforeEach
    void setUp() throws IOException {
        dir = tmp.resolve("library");
        service = new LibraryService(dir, store);
        when(store.findByPublicId(ID)).thenReturn(Optional.of(LibraryAddonEntity.builder().publicId(ID).name("Details").build()));

        // 항목 하나를 파일 단위 저장 형태로 만들어 둔다
        Path item = dir.resolve(ID);
        Files.createDirectories(item.resolve("data/libs"));
        Files.writeString(item.resolve("data/Details.toc"), "## Version: 1\n", StandardCharsets.UTF_8);
        Files.writeString(item.resolve("data/libs/a.lua"), "x", StandardCharsets.UTF_8);
        LibraryLayout.writeManifest(item, List.of(new FileSpec("Details.toc", 14, "a".repeat(64)), new FileSpec("libs/a.lua", 1, "b".repeat(64))));
        // 폴더 밖에 있는 비밀 파일(목록에도 없다)
        Files.writeString(dir.resolve("secret.txt"), "비밀", StandardCharsets.UTF_8);
    }

    @Test
    void 파일_목록을_경로_크기_해시로_내려준다() {
        List<FileResponse> files = service.manifest(ID);

        assertThat(files).extracting(FileResponse::getPath).containsExactly("Details.toc", "libs/a.lua");
        assertThat(files.get(1).getSize()).isEqualTo(1);
        assertThat(files.get(1).getSha256()).isEqualTo("b".repeat(64));
    }

    /** 묶음을 만들어 [경로 길이 2][경로][크기 8][내용] 형식을 읽어 경로 → 내용으로 돌려준다. */
    private java.util.Map<String, String> bundle(String... paths) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LibraryService.writeBundle(service.prepareBundle(ID, List.of(paths)), out);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        while (in.available() > 0) {
            byte[] path = new byte[in.readUnsignedShort()];
            in.readFully(path);
            byte[] data = new byte[(int) in.readLong()];
            in.readFully(data);
            result.put(new String(path, StandardCharsets.UTF_8), new String(data, StandardCharsets.UTF_8));
        }
        return result;
    }

    @Test
    void 목록에_있는_파일만_한_묶음으로_내려준다() throws IOException {
        // 같은 경로를 두 번 달라고 해도 한 번만 담는다
        assertThat(bundle("libs/a.lua", "Details.toc", "libs/a.lua")).containsExactly(
                java.util.Map.entry("libs/a.lua", "x"), java.util.Map.entry("Details.toc", "## Version: 1\n"));
    }

    @Test
    void 목록에_없거나_폴더_밖으로_나가는_경로가_하나라도_있으면_거부한다() {
        assertThatThrownBy(() -> bundle("libs/a.lua", "libs/other.lua")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> bundle("../../secret.txt")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> bundle("/etc/passwd")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> bundle("C:/Windows/win.ini")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> bundle("..\\secret.txt")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.prepareBundle(ID, List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.prepareBundle(ID, java.util.Collections.nCopies(LibraryService.MAX_BUNDLE_PATHS + 1, "Details.toc")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 디스크의_파일이_목록의_크기보다_짧으면_묶음을_끊는다() throws IOException {
        Path item = dir.resolve(ID);
        LibraryLayout.writeManifest(item, List.of(new FileSpec("Details.toc", 14, "a".repeat(64)), new FileSpec("libs/a.lua", 99, "b".repeat(64))));

        assertThatThrownBy(() -> bundle("libs/a.lua")).isInstanceOf(IOException.class).hasMessageContaining("목록보다 짧습니다");
    }

    @Test
    void 목록에_없는_항목이나_경로_문자가_섞인_ID_는_거부한다() {
        assertThatThrownBy(() -> service.manifest("../library")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.prepareBundle("not-a-uuid", List.of("a"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.manifest("ffffffff-2222-3333-4444-55555555abcd"))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 저장_폴더가_설정되지_않으면_기능이_꺼진다() {
        LibraryService disabled = new LibraryService((Path) null, store);

        assertThatThrownBy(() -> disabled.manifest(ID))
                .isInstanceOf(BusinessException.class).hasMessageContaining("LIBRARY_DIR");
    }

    @Test
    void 지우면_DB_와_파일을_모두_지운다() throws IOException {
        Files.writeString(dir.resolve(ID + ".zip"), "옛 형식", StandardCharsets.UTF_8);

        service.delete(ID, 1L);

        verify(store).delete(ID);
        assertThat(dir.resolve(ID)).doesNotExist();
        assertThat(dir.resolve(ID + ".zip")).doesNotExist();
        assertThat(dir.resolve("secret.txt")).exists();       // 다른 파일은 건드리지 않는다
    }

    @Test
    void 경로가_섞인_ID_로는_지울_수_없다() {
        assertThatThrownBy(() -> service.delete("../secret.txt", 1L)).isInstanceOf(BusinessException.class);
        verify(store, never()).delete(any());
        assertThat(dir.resolve("secret.txt")).exists();
    }

    @Test
    void 같이_설치할_애드온은_폴더_이름만_받는다() {
        assertThatThrownBy(() -> service.setExtraRequires(ID, List.of("../evil")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("올바른 폴더 이름");
        assertThatThrownBy(() -> service.setExtraRequires(ID, List.of("a/b"))).isInstanceOf(BusinessException.class);
        verify(store, never()).updateExtraRequires(eq(ID), any());
    }

    @Test
    void 같이_설치할_애드온을_지정하면_저장하고_응답에_합쳐_보인다() {
        LibraryAddonEntity entity = LibraryAddonEntity.builder().publicId(ID).name("Questie").requires("LibA").extraRequires("QuestieDB").build();
        when(store.updateExtraRequires(ID, "QuestieDB,QuestieTrace")).thenReturn(Optional.of(entity));

        LibraryAddonResponse r = service.setExtraRequires(ID, List.of(" QuestieDB ", "QuestieTrace", "QuestieDB"));

        assertThat(r.getRequires()).containsExactly("LibA", "QuestieDB");
        assertThat(r.getExtraRequires()).containsExactly("QuestieDB");
    }
}
