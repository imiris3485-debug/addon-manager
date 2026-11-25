package com.shapinel.addonmanager.common.domain.repository;

import com.shapinel.addonmanager.common.domain.entity.AddonEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AddonEventRepository extends JpaRepository<AddonEventEntity, Long> {

    /** 최근 이력부터(Pageable 로 개수 제한) */
    List<AddonEventEntity> findByAccountIdOrderByIdDesc(Long accountId, Pageable pageable);
}
