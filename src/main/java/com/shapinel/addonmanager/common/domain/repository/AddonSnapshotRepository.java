package com.shapinel.addonmanager.common.domain.repository;

import com.shapinel.addonmanager.common.domain.entity.AddonSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AddonSnapshotRepository extends JpaRepository<AddonSnapshotEntity, Long> {

    List<AddonSnapshotEntity> findByAccountIdOrderByIdDesc(Long accountId);

    /** 가장 최근의 해당 종류 기록(자동 기록이 직전과 같은 목록인지 비교할 때 쓴다) */
    Optional<AddonSnapshotEntity> findFirstByAccountIdAndSourceOrderByIdDesc(Long accountId, String source);

    /** 오래된 것부터(자동 기록을 개수 제한에 맞춰 지울 때 쓴다) */
    List<AddonSnapshotEntity> findByAccountIdAndSourceOrderByIdAsc(Long accountId, String source);

    long countByAccountIdAndSource(Long accountId, String source);

    Optional<AddonSnapshotEntity> findByPublicId(String publicId);
}
