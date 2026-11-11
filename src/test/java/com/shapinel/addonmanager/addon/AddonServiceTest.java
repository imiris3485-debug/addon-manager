package com.shapinel.addonmanager.addon;

import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FileIndex;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Mod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증하는 정책:
 * - 최신 파일은 환경의 게임 버전과 일치하는 것만 고른다.
 * - 안정(release) 파일이 있으면 더 새로운 beta/alpha 보다 우선한다.
 * - 폴더명에 경로 구분자·상위 경로가 들어 있으면 거부한다(경로 조작 방지).
 */
class AddonServiceTest {

    private static Mod modWith(FileIndex... indexes) {
        return new Mod(1L, "Test", "test", "", null, 0L, true, List.of(), null, List.of(indexes));
    }

    @Test
    void 게임_버전이_일치하는_파일만_고른다() {
        Mod mod = modWith(
                new FileIndex("3.3.5", 100L, "a.zip", 1),
                new FileIndex("1.14.4", 200L, "b.zip", 1));

        assertThat(AddonService.pickLatest(mod, "3.3.5")).get().extracting(FileIndex::fileId).isEqualTo(100L);
    }

    @Test
    void 같은_버전에서는_가장_새_파일을_고른다() {
        Mod mod = modWith(
                new FileIndex("3.3.5", 100L, "a.zip", 1),
                new FileIndex("3.3.5", 150L, "b.zip", 1));

        assertThat(AddonService.pickLatest(mod, "3.3.5")).get().extracting(FileIndex::fileId).isEqualTo(150L);
    }

    @Test
    void 안정판이_있으면_더_새로운_베타보다_우선한다() {
        Mod mod = modWith(
                new FileIndex("3.3.5", 100L, "release.zip", 1),
                new FileIndex("3.3.5", 300L, "beta.zip", 2));

        assertThat(AddonService.pickLatest(mod, "3.3.5")).get().extracting(FileIndex::fileId).isEqualTo(100L);
    }

    @Test
    void 안정판이_없으면_베타를_고른다() {
        Mod mod = modWith(new FileIndex("3.3.5", 300L, "beta.zip", 2));

        assertThat(AddonService.pickLatest(mod, "3.3.5")).get().extracting(FileIndex::fileId).isEqualTo(300L);
    }

    @Test
    void 해당_버전_파일이_없으면_비어_있다() {
        Mod mod = modWith(new FileIndex("1.14.4", 200L, "b.zip", 1));

        assertThat(AddonService.pickLatest(mod, "3.3.5")).isEmpty();
    }

    @Test
    void 폴더명에_경로_조작_문자가_있으면_거부한다() {
        assertThat(AddonService.isSafeFolderName("Details")).isTrue();
        assertThat(AddonService.isSafeFolderName("..")).isFalse();
        assertThat(AddonService.isSafeFolderName("../evil")).isFalse();
        assertThat(AddonService.isSafeFolderName("a\\b")).isFalse();
        assertThat(AddonService.isSafeFolderName("C:")).isFalse();
        assertThat(AddonService.isSafeFolderName(" ")).isFalse();
    }
}
