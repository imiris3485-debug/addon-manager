package com.shapinel.addonmanager.common.domain.repository;

import com.shapinel.addonmanager.common.domain.entity.GameEnvironmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GameEnvironmentRepository extends JpaRepository<GameEnvironmentEntity, Long> {

    List<GameEnvironmentEntity> findByAccountIdAndDeletedAtIsNullOrderByIdAsc(Long accountId);

    /** 환경은 항상 소유 계정과 함께 조회한다(다른 계정의 환경 접근 차단). */
    Optional<GameEnvironmentEntity> findByIdAndAccountIdAndDeletedAtIsNull(Long id, Long accountId);
}
