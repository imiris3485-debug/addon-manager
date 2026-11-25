package com.shapinel.addonmanager.addon;

import com.shapinel.addonmanager.addon.InstalledAddonStore.Snapshot;
import com.shapinel.addonmanager.addon.domain.param.AddonParams.ScanEntry;
import com.shapinel.addonmanager.addon.domain.param.AddonParams.ScanParam;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.InstalledAddonResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.ScanResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.UpdateStatus;
import com.shapinel.addonmanager.common.domain.entity.GameEnvironmentEntity;
import com.shapinel.addonmanager.common.domain.entity.InstalledAddonEntity;
import com.shapinel.addonmanager.curseforge.CurseForgeClient;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FileIndex;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Mod;
import com.shapinel.addonmanager.environment.EnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 스캔 매칭 정책
 * - 폴더는 지문 > .toc 의 프로젝트 ID > 이 앱의 설치 기록(폴더 이름) 순으로 프로젝트와 연결한다.
 *   (.toc 가 껍데기이거나 지문이 맞지 않는 애드온도 이 앱이 설치했다면 알아본다)
 * - 기록에도 없는 폴더는 연결하지 않는다.
 * - 스냅샷 교체가 기록으로 알아본 애드온을 지우지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class AddonServiceScanTest {

    private static final long ENV_ID = 5L;
    private static final long ACCOUNT_ID = 1L;
    private static final long QUESTIE = 334372L;

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

    private static ScanEntry entry(String folder, String version, Long projectId) {
        ScanEntry e = new ScanEntry();
        e.setFolderName(folder);
        e.setTocVersion(version);
        e.setProjectId(projectId);
        return e;
    }

    private static ScanParam param(ScanEntry... entries) {
        ScanParam p = new ScanParam();
        p.setEntries(List.of(entries));
        return p;
    }

    private static InstalledAddonEntity record(long projectId, long fileId, String tocVersion, String folders) {
        return InstalledAddonEntity.builder().environmentId(ENV_ID).projectId(projectId)
                .installedFileId(fileId).tocVersion(tocVersion).folders(folders).build();
    }

    private static Mod mod(long id, String name, long latestFileId) {
        return new Mod(id, name, name.toLowerCase(), "", null, 0L, true, List.of(), null,
                List.of(new FileIndex("1.60.1", latestFileId, name + ".zip", 1)));
    }

    @Test
    void toc_와_지문으로_모르는_폴더도_설치_기록의_폴더_이름으로_알아본다() {
        // Questie 처럼 Questie.toc 가 껍데기라 프로젝트 ID 도 지문도 없는 경우
        when(store.findByProject(ENV_ID)).thenReturn(Map.of(QUESTIE, record(QUESTIE, 900L, "12.14.0", "Questie")));
        when(curseForge.getMods(List.of(QUESTIE))).thenReturn(Map.of(QUESTIE, mod(QUESTIE, "Questie", 900L)));

        ScanResponse result = service.scan(ACCOUNT_ID, ENV_ID, param(entry("Questie", "12.14.0", null)));

        assertThat(result.getUnmatchedFolders()).isEmpty();
        assertThat(result.getAddons()).hasSize(1);
        InstalledAddonResponse addon = result.getAddons().get(0);
        assertThat(addon.getProjectId()).isEqualTo(QUESTIE);
        assertThat(addon.getInstalledFileId()).isEqualTo(900L);
        assertThat(addon.getStatus()).isEqualTo(UpdateStatus.UP_TO_DATE);
    }

    @Test
    void 기록에도_없는_폴더는_연결하지_않는다() {
        when(store.findByProject(ENV_ID)).thenReturn(Map.of());

        ScanResponse result = service.scan(ACCOUNT_ID, ENV_ID, param(entry("MysteryAddon", null, null)));

        assertThat(result.getAddons()).isEmpty();
        assertThat(result.getUnmatchedFolders()).containsExactly("MysteryAddon");
    }

    @Test
    void 폴더_이름_비교는_대소문자를_구분하지_않는다() {
        when(store.findByProject(ENV_ID)).thenReturn(Map.of(QUESTIE, record(QUESTIE, 900L, "12.14.0", "Questie")));
        when(curseForge.getMods(List.of(QUESTIE))).thenReturn(Map.of(QUESTIE, mod(QUESTIE, "Questie", 900L)));

        ScanResponse result = service.scan(ACCOUNT_ID, ENV_ID, param(entry("questie", "12.14.0", null)));

        assertThat(result.getAddons()).hasSize(1);
    }

    @Test
    void toc_에_프로젝트_ID_가_있으면_기록보다_우선한다() {
        long other = 777L;
        when(store.findByProject(ENV_ID)).thenReturn(Map.of(QUESTIE, record(QUESTIE, 900L, "1", "Shared")));
        when(curseForge.getMods(List.of(other))).thenReturn(Map.of(other, mod(other, "Other", 10L)));

        ScanResponse result = service.scan(ACCOUNT_ID, ENV_ID, param(entry("Shared", "1", other)));

        assertThat(result.getAddons()).extracting(InstalledAddonResponse::getProjectId).containsExactly(other);
    }

    @Test
    void 기록으로_알아본_애드온은_스냅샷_교체에서_지워지지_않는다() {
        when(store.findByProject(ENV_ID)).thenReturn(Map.of(QUESTIE, record(QUESTIE, 900L, "12.14.0", "Questie")));
        when(curseForge.getMods(List.of(QUESTIE))).thenReturn(Map.of(QUESTIE, mod(QUESTIE, "Questie", 900L)));

        service.scan(ACCOUNT_ID, ENV_ID, param(entry("Questie", "12.14.0", null)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Snapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).replaceSnapshot(anyLong(), captor.capture());
        assertThat(captor.getValue()).extracting(Snapshot::projectId).containsExactly(QUESTIE);
    }

    @Test
    void 기록한_버전과_toc_버전이_다르면_설치_파일을_믿지_않는다() {
        // 사용자가 폴더를 직접 바꿨을 수 있으므로 .toc 버전이 그대로일 때만 기록한 파일 ID 를 쓴다
        when(store.findByProject(ENV_ID)).thenReturn(Map.of(QUESTIE, record(QUESTIE, 900L, "12.14.0", "Questie")));
        when(curseForge.getMods(List.of(QUESTIE))).thenReturn(Map.of(QUESTIE, mod(QUESTIE, "Questie", 900L)));

        ScanResponse result = service.scan(ACCOUNT_ID, ENV_ID, param(entry("Questie", "99.0.0", null)));

        assertThat(result.getAddons().get(0).getStatus()).isEqualTo(UpdateStatus.UNKNOWN);
    }
}
