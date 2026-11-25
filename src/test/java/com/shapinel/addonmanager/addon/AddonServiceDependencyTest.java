package com.shapinel.addonmanager.addon;

import com.shapinel.addonmanager.addon.domain.response.AddonResponses.DependencyResponse;
import com.shapinel.addonmanager.common.domain.entity.GameEnvironmentEntity;
import com.shapinel.addonmanager.common.domain.entity.InstalledAddonEntity;
import com.shapinel.addonmanager.curseforge.CurseForgeClient;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Dependency;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FileIndex;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Mod;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.ModFile;
import com.shapinel.addonmanager.environment.EnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 필수 의존성 정책
 * - 필수(3) 의존성만 따라간다. 선택(2)·내장 라이브러리(1)는 받지 않는다.
 * - 의존성의 의존성까지 따라가되, 순환하거나 같은 프로젝트가 여러 번 나와도 한 번만 담고 끝없이 돌지 않는다.
 * - 환경의 게임 버전용 파일이 없으면 latestFileId 를 null 로 알리고, 외부 배포를 막은 애드온은 downloadable=false 로 알린다.
 * - 따라가는 의존성 수에 상한이 있다.
 */
@ExtendWith(MockitoExtension.class)
class AddonServiceDependencyTest {

    private static final long ACCOUNT_ID = 1L;
    private static final long ENV_ID = 5L;

    @Mock
    EnvironmentService environmentService;
    @Mock
    InstalledAddonStore store;
    @Mock
    CurseForgeClient curseForge;

    AddonService service;

    @BeforeEach
    void setUp() {
        service = new AddonService(environmentService, store, curseForge);
        when(environmentService.getOwned(ACCOUNT_ID, ENV_ID))
                .thenReturn(GameEnvironmentEntity.builder().accountId(ACCOUNT_ID).name("내 PC").gameVersion("1.60.1").build());
    }

    private static Dependency required(long modId) {
        return new Dependency(modId, Dependency.REQUIRED);
    }

    private static ModFile file(long modId, long fileId, Dependency... deps) {
        return new ModFile(fileId, modId, "f", "f.zip", null, "https://edge.forgecdn.net/f.zip", null, List.of(deps));
    }

    private static Mod mod(long id, boolean distributable, Long latestFileId) {
        List<FileIndex> indexes = latestFileId == null ? List.of() : List.of(new FileIndex("1.60.1", latestFileId, "m" + id + ".zip", 1));
        return new Mod(id, "Mod" + id, "mod" + id, "", null, 0L, distributable, List.of(), null, indexes);
    }

    @Test
    void 필수_의존성만_따라가고_선택과_내장은_제외한다() {
        when(curseForge.getFile(1L, 100L)).thenReturn(file(1L, 100L,
                required(2L), new Dependency(3L, 2), new Dependency(4L, 1), new Dependency(5L, 6)));
        when(curseForge.getMods(List.of(2L))).thenReturn(Map.of(2L, mod(2L, true, 200L)));
        when(curseForge.getFile(2L, 200L)).thenReturn(file(2L, 200L));

        List<DependencyResponse> result = service.getRequiredDependencies(ACCOUNT_ID, ENV_ID, 1L, 100L);

        assertThat(result).extracting(DependencyResponse::getProjectId).containsExactly(2L);
        assertThat(result.get(0).getLatestFileId()).isEqualTo(200L);
        assertThat(result.get(0).isDownloadable()).isTrue();
    }

    @Test
    void 의존성의_의존성까지_가까운_것부터_모은다() {
        when(curseForge.getFile(1L, 100L)).thenReturn(file(1L, 100L, required(2L)));
        when(curseForge.getMods(List.of(2L))).thenReturn(Map.of(2L, mod(2L, true, 200L)));
        when(curseForge.getFile(2L, 200L)).thenReturn(file(2L, 200L, required(5L)));
        when(curseForge.getMods(List.of(5L))).thenReturn(Map.of(5L, mod(5L, true, 500L)));
        when(curseForge.getFile(5L, 500L)).thenReturn(file(5L, 500L));

        List<DependencyResponse> result = service.getRequiredDependencies(ACCOUNT_ID, ENV_ID, 1L, 100L);

        assertThat(result).extracting(DependencyResponse::getProjectId).containsExactly(2L, 5L);
    }

    @Test
    void 순환_의존성이어도_끝없이_돌지_않는다() {
        // 1 → 2 → (1, 2 자신)
        when(curseForge.getFile(1L, 100L)).thenReturn(file(1L, 100L, required(2L)));
        when(curseForge.getMods(List.of(2L))).thenReturn(Map.of(2L, mod(2L, true, 200L)));
        when(curseForge.getFile(2L, 200L)).thenReturn(file(2L, 200L, required(1L), required(2L)));

        List<DependencyResponse> result = service.getRequiredDependencies(ACCOUNT_ID, ENV_ID, 1L, 100L);

        assertThat(result).extracting(DependencyResponse::getProjectId).containsExactly(2L);
    }

    @Test
    void 같은_프로젝트가_여러_경로로_나와도_한_번만_담는다() {
        when(curseForge.getFile(1L, 100L)).thenReturn(file(1L, 100L, required(2L), required(3L)));
        when(curseForge.getMods(List.of(2L, 3L))).thenReturn(Map.of(2L, mod(2L, true, 200L), 3L, mod(3L, true, 300L)));
        when(curseForge.getFile(2L, 200L)).thenReturn(file(2L, 200L, required(9L)));
        when(curseForge.getFile(3L, 300L)).thenReturn(file(3L, 300L, required(9L)));
        when(curseForge.getMods(List.of(9L))).thenReturn(Map.of(9L, mod(9L, true, 900L)));
        when(curseForge.getFile(9L, 900L)).thenReturn(file(9L, 900L));

        List<DependencyResponse> result = service.getRequiredDependencies(ACCOUNT_ID, ENV_ID, 1L, 100L);

        assertThat(result).extracting(DependencyResponse::getProjectId).containsExactly(2L, 3L, 9L);
    }

    @Test
    void 이_게임_버전용_파일이_없으면_파일_ID_를_null_로_알린다() {
        when(curseForge.getFile(1L, 100L)).thenReturn(file(1L, 100L, required(2L)));
        when(curseForge.getMods(List.of(2L))).thenReturn(Map.of(2L, mod(2L, true, null)));

        List<DependencyResponse> result = service.getRequiredDependencies(ACCOUNT_ID, ENV_ID, 1L, 100L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLatestFileId()).isNull();
    }

    @Test
    void 외부_배포를_막은_애드온은_downloadable_false_이고_그_안은_따라가지_않는다() {
        when(curseForge.getFile(1L, 100L)).thenReturn(file(1L, 100L, required(2L)));
        when(curseForge.getMods(List.of(2L))).thenReturn(Map.of(2L, mod(2L, false, 200L)));

        List<DependencyResponse> result = service.getRequiredDependencies(ACCOUNT_ID, ENV_ID, 1L, 100L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isDownloadable()).isFalse();
        // getFile(2L, 200L) 을 스텁하지 않았으므로 호출했다면 null 이 되어 이후 단계가 실패한다
    }

    @Test
    void CurseForge_에서_사라진_프로젝트는_건너뛴다() {
        when(curseForge.getFile(1L, 100L)).thenReturn(file(1L, 100L, required(2L)));
        when(curseForge.getMods(List.of(2L))).thenReturn(Map.of());

        assertThat(service.getRequiredDependencies(ACCOUNT_ID, ENV_ID, 1L, 100L)).isEmpty();
    }

    @Test
    void 의존성_정보가_없는_파일은_빈_목록이다() {
        when(curseForge.getFile(1L, 100L)).thenReturn(new ModFile(100L, 1L, "f", "f.zip", null, null, null, null));

        assertThat(service.getRequiredDependencies(ACCOUNT_ID, ENV_ID, 1L, 100L)).isEmpty();
    }

    @Test
    void 따라가는_의존성_수에_상한이_있다() {
        // 모든 인자에 맞는 스텁을 먼저 둔다. 나중에 선언한 스텁이 우선하므로 루트 파일 스텁이 이것을 덮어쓴다.
        lenient().when(curseForge.getFile(anyLong(), anyLong())).thenReturn(file(0L, 0L));
        Dependency[] many = LongStream.rangeClosed(100, 129).mapToObj(AddonServiceDependencyTest::required).toArray(Dependency[]::new);
        when(curseForge.getFile(1L, 1000L)).thenReturn(file(1L, 1000L, many));
        Map<Long, Mod> mods = new HashMap<>();
        LongStream.rangeClosed(100, 129).forEach(id -> mods.put(id, mod(id, true, id * 10)));
        when(curseForge.getMods(anyList())).thenReturn(mods);

        List<DependencyResponse> result = service.getRequiredDependencies(ACCOUNT_ID, ENV_ID, 1L, 1000L);

        assertThat(result).hasSize(AddonService.MAX_DEPENDENCIES);
    }

    @Test
    void 설치된_프로젝트_ID_는_정렬해서_돌려준다() {
        when(store.findByProject(ENV_ID)).thenReturn(Map.of(
                30L, InstalledAddonEntity.builder().projectId(30L).folders("C").build(),
                10L, InstalledAddonEntity.builder().projectId(10L).folders("A").build(),
                20L, InstalledAddonEntity.builder().projectId(20L).folders("B").build()));

        assertThat(service.getInstalledProjectIds(ACCOUNT_ID, ENV_ID)).containsExactly(10L, 20L, 30L);
    }
}
