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
import org.springframework.core.io.Resource;

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
 * - 받는 쪽에는 "파일 목록(경로·크기·SHA-256)"과 목록에 있는 파일만 내려준다. 목록에 없는 경로나 폴더 밖으로 나가는 경로는 거부한다.
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
        LibraryLayout.writeManifest(item, List.of(new FileSpec("Details.toc", 13, "a".repeat(64)), new FileSpec("libs/a.lua", 1, "b".repeat(64))));
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

    @Test
    void 목록에_있는_파일만_내려준다() throws IOException {
        Resource r = service.file(ID, "libs/a.lua");

        assertThat(r.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("x");
    }

    @Test
    void 목록에_없거나_폴더_밖으로_나가는_경로는_거부한다() {
        assertThatThrownBy(() -> service.file(ID, "libs/other.lua")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.file(ID, "../../secret.txt")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.file(ID, "/etc/passwd")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.file(ID, "C:/Windows/win.ini")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.file(ID, "..\\secret.txt")).isInstanceOf(BusinessException.class);
    }

    @Test
    void 목록에_없는_항목이나_경로_문자가_섞인_ID_는_거부한다() {
        assertThatThrownBy(() -> service.manifest("../library")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.file("not-a-uuid", "a")).isInstanceOf(BusinessException.class);
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
