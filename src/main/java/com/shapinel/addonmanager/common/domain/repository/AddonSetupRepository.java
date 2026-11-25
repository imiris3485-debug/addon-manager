package com.shapinel.addonmanager.common.domain.repository;

import com.shapinel.addonmanager.common.domain.entity.AddonSetupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AddonSetupRepository extends JpaRepository<AddonSetupEntity, Long> {

    List<AddonSetupEntity> findByOwnerIdOrderByIdDesc(Long ownerId);

    List<AddonSetupEntity> findByIdInOrderByIdDesc(Collection<Long> ids);

    Optional<AddonSetupEntity> findByPublicId(String publicId);

    long countByOwnerId(Long ownerId);
}
