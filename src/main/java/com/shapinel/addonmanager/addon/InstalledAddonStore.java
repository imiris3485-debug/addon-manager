package com.shapinel.addonmanager.addon;

import com.shapinel.addonmanager.common.domain.entity.InstalledAddonEntity;
import com.shapinel.addonmanager.common.domain.repository.InstalledAddonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 설치 기록 저장 전용. CurseForge 호출(느린 외부 I/O)이 DB 트랜잭션에 묶이지 않도록
 * AddonService 와 분리했다.
 */
@Component
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class InstalledAddonStore {

    public record Snapshot(Long projectId, Long fileId, String tocVersion, List<String> folders) {
    }

    private final InstalledAddonRepository repository;

    @Transactional(readOnly = true)
    public Map<Long, InstalledAddonEntity> findByProject(Long environmentId) {
        Map<Long, InstalledAddonEntity> map = new HashMap<>();
        repository.findByEnvironmentIdAndDeletedAtIsNull(environmentId).forEach(e -> map.put(e.getProjectId(), e));
        return map;
    }

    /** 스캔 결과로 스냅샷을 교체한다. 폴더가 사라진 애드온의 기록은 지운다. */
    public void replaceSnapshot(Long environmentId, List<Snapshot> snapshots) {
        Map<Long, InstalledAddonEntity> existing = findByProject(environmentId);
        Map<Long, Snapshot> incoming = new HashMap<>();
        snapshots.forEach(s -> incoming.put(s.projectId(), s));

        existing.values().stream()
                .filter(e -> !incoming.containsKey(e.getProjectId()))
                .forEach(repository::delete);
        incoming.values().forEach(s -> upsert(environmentId, s));
    }

    public void upsert(Long environmentId, Snapshot s) {
        String folders = String.join(",", s.folders());
        repository.findByEnvironmentIdAndProjectIdAndDeletedAtIsNull(environmentId, s.projectId())
                .ifPresentOrElse(
                        e -> e.update(s.fileId(), s.tocVersion(), folders),
                        () -> repository.save(InstalledAddonEntity.builder()
                                .environmentId(environmentId)
                                .projectId(s.projectId())
                                .installedFileId(s.fileId())
                                .tocVersion(s.tocVersion())
                                .folders(folders)
                                .build()));
    }

    public void remove(Long environmentId, Long projectId) {
        repository.findByEnvironmentIdAndProjectIdAndDeletedAtIsNull(environmentId, projectId)
                .ifPresent(repository::delete);
    }
}
