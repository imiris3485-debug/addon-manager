package com.shapinel.addonmanager.common.domain.repository;

import com.shapinel.addonmanager.common.domain.entity.LibraryAddonEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LibraryAddonRepository extends JpaRepository<LibraryAddonEntity, Long> {

    List<LibraryAddonEntity> findByDeletedAtIsNullOrderByNameAsc();

    Optional<LibraryAddonEntity> findByPublicIdAndDeletedAtIsNull(String publicId);

    Optional<LibraryAddonEntity> findByNameIgnoreCaseAndDeletedAtIsNull(String name);
}
