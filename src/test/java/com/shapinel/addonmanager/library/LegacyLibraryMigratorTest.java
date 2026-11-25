package com.shapinel.addonmanager.library;

import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 옛 형식(zip 한 덩어리) 자료실 항목을 파일 단위 저장으로 바꾸는 정책
 * - 바꾼 뒤 파일 목록·파일이 생기고 zip 은 지워진다.
 * - 바꾼 내용의 해시가 DB 에 기록된 해시와 다르거나 zip 안에 허용하지 않는 파일이 있으면 바꾸지 않고 zip 도 남겨 둔다.
 * - 이미 새 형식이 있으면 남은 zip 만 지운다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LegacyLibraryMigratorTest {

    private static final String ID = "1a2b3c4d-2222-3333-4444-55555555abcd";

    @TempDir
    Path tmp;

    @Mock
    LibraryStore store;

    Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = tmp.resolve("library");
        Files.createDirectories(dir);
    }

    private Path zip(String... pairs) throws IOException {
        Path zip = dir.resolve(ID + ".zip");
        try (OutputStream o = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(o)) {
            for (int i = 0; i < pairs.length; i += 2) {
                out.putNextEntry(new ZipEntry(pairs[i]));
                out.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    private LegacyLibraryMigrator migratorFor(String hash) {
        when(store.findAll()).thenReturn(List.of(LibraryAddonEntity.builder().publicId(ID).name("Details").contentHash(hash).build()));
        return new LegacyLibraryMigrator(dir.toString(), store);
    }

    @Test
    void zip_항목을_파일_단위로_바꾸고_zip_은_지운다() throws IOException {
        Path zip = zip("Details/Details.toc", "## Version: 1\n", "Details/libs/a.lua", "code");
        String hash = AddonZipInspector.inspect(zip).contentHash();

        migratorFor(hash).migrate();

        assertThat(Files.readString(dir.resolve(ID + "/data/libs/a.lua"))).isEqualTo("code");
        assertThat(LibraryLayout.readManifest(dir.resolve(ID))).extracting(LibraryLayout.FileSpec::path).containsExactly("Details.toc", "libs/a.lua");
        assertThat(LibraryLayout.contentHash("Details", LibraryLayout.readManifest(dir.resolve(ID)))).isEqualTo(hash);     // 새 형식에서 계산한 해시도 같다
        assertThat(zip).doesNotExist();
    }

    @Test
    void 해시가_기록과_다르면_바꾸지_않고_zip_을_남긴다() throws IOException {
        Path zip = zip("Details/Details.toc", "## Version: 1\n");

        migratorFor("0".repeat(64)).migrate();

        assertThat(dir.resolve(ID)).doesNotExist();
        assertThat(zip).exists();
    }

    @Test
    void 허용하지_않는_파일이_든_zip_은_바꾸지_않는다() throws IOException {
        Path zip = zip("Details/Details.toc", "x", "Details/run.exe", "MZ");

        migratorFor("0".repeat(64)).migrate();

        assertThat(dir.resolve(ID)).doesNotExist();
        assertThat(zip).exists();
    }

    @Test
    void 이미_새_형식이_있으면_남은_zip_만_지운다() throws IOException {
        Path zip = zip("Details/Details.toc", "x");
        Files.createDirectories(dir.resolve(ID + "/data"));
        LibraryLayout.writeManifest(dir.resolve(ID), List.of(new LibraryLayout.FileSpec("Details.toc", 1, "a".repeat(64))));

        migratorFor("0".repeat(64)).migrate();

        assertThat(zip).doesNotExist();
        assertThat(dir.resolve(ID + "/manifest.txt")).exists();
    }
}
