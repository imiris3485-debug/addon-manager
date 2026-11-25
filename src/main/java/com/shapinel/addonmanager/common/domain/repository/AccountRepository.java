package com.shapinel.addonmanager.common.domain.repository;

import com.shapinel.addonmanager.common.domain.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    /** 소프트 삭제된 계정은 로그인할 수 없다. */
    Optional<AccountEntity> findByLoginIdAndDeletedAtIsNull(String loginId);

    /** 로그인 ID 유니크 제약은 삭제된 계정까지 포함하므로 중복 확인도 삭제 여부와 무관하게 한다. */
    boolean existsByLoginId(String loginId);

    /** 세팅 공유 대상 후보(승인된 계정) */
    List<AccountEntity> findByStatusAndDeletedAtIsNullOrderByNameAsc(AccountEntity.Status status);

    /** 공유 대상 지정: 로그인 ID 로 승인된 계정만 찾는다 */
    List<AccountEntity> findByLoginIdInAndStatusAndDeletedAtIsNull(Collection<String> loginIds, AccountEntity.Status status);
}
