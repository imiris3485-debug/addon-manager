package com.shapinel.addonmanager.common.domain.repository;

import com.shapinel.addonmanager.common.domain.entity.AddonSetupTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AddonSetupTargetRepository extends JpaRepository<AddonSetupTargetEntity, Long> {

    List<AddonSetupTargetEntity> findBySetupIdIn(Collection<Long> setupIds);

    List<AddonSetupTargetEntity> findByAccountId(Long accountId);

    boolean existsBySetupIdAndAccountId(Long setupId, Long accountId);

    Optional<AddonSetupTargetEntity> findBySetupIdAndAccountId(Long setupId, Long accountId);

    void deleteBySetupId(Long setupId);
}
