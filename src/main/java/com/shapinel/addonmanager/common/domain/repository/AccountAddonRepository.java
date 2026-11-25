package com.shapinel.addonmanager.common.domain.repository;

import com.shapinel.addonmanager.common.domain.entity.AccountAddonEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountAddonRepository extends JpaRepository<AccountAddonEntity, Long> {

    List<AccountAddonEntity> findByEnvironmentIdOrderByAddonNameAsc(Long environmentId);

    Optional<AccountAddonEntity> findByEnvironmentIdAndAddonName(Long environmentId, String addonName);
}
