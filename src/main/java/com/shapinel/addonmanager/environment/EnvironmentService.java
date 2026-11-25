package com.shapinel.addonmanager.environment;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.GameEnvironmentEntity;
import com.shapinel.addonmanager.common.domain.repository.GameEnvironmentRepository;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.environment.domain.param.EnvironmentParams.EnvironmentSaveParam;
import com.shapinel.addonmanager.environment.domain.response.EnvironmentResponses.EnvironmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class EnvironmentService {

    private final GameEnvironmentRepository repository;

    @Transactional(readOnly = true)
    public List<EnvironmentResponse> getEnvironments(Long accountId) {
        return repository.findByAccountIdAndDeletedAtIsNullOrderByIdAsc(accountId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 다른 서비스에서도 쓰는 소유권 검증 조회. 계정이 다르면 존재 여부를 숨기려고 NOT_FOUND 로 응답한다.
     */
    @Transactional(readOnly = true)
    public GameEnvironmentEntity getOwned(Long accountId, Long environmentId) {
        return repository.findByIdAndAccountIdAndDeletedAtIsNull(environmentId, accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    public EnvironmentResponse create(Long accountId, EnvironmentSaveParam param) {
        GameEnvironmentEntity saved = repository.save(GameEnvironmentEntity.builder()
                .accountId(accountId)
                .name(param.getName().trim())
                .gameVersion(param.getGameVersion().trim())
                .build());
        return toResponse(saved);
    }

    public void update(Long accountId, Long environmentId, EnvironmentSaveParam param) {
        getOwned(accountId, environmentId).update(param.getName().trim(), param.getGameVersion().trim());
    }

    public void delete(Long accountId, Long environmentId) {
        // BaseEntity 의 deleted_at 은 JPA 가 쓸 수 없는 컬럼(DB 관리)이라 환경은 물리 삭제한다.
        // ⚠️ 예전 installed_addon 테이블이 남아 있고 이 환경을 FK 로 가리키는 행이 있으면 삭제가 실패한다.
        //    docs/sql/drop-installed-addon.sql 로 그 테이블을 지운다(CurseForge 연동 제거로 더는 쓰지 않는다).
        GameEnvironmentEntity env = getOwned(accountId, environmentId);
        repository.delete(env);
    }

    private EnvironmentResponse toResponse(GameEnvironmentEntity e) {
        return new EnvironmentResponse(e.getId(), e.getName(), e.getGameVersion());
    }
}
