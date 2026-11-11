package com.shapinel.addonmanager.common.domain.repository;

import com.shapinel.addonmanager.common.domain.entity.InstalledAddonEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstalledAddonRepository extends JpaRepository<InstalledAddonEntity, Long> {

    List<InstalledAddonEntity> findByEnvironmentIdAndDeletedAtIsNull(Long environmentId);

    Optional<InstalledAddonEntity> findByEnvironmentIdAndProjectIdAndDeletedAtIsNull(Long environmentId, Long projectId);

    /** 설치 기록은 스냅샷이라 물리 삭제한다(유니크 키가 소프트 삭제 행과 충돌하지 않도록). */
    void deleteByEnvironmentId(Long environmentId);
}
