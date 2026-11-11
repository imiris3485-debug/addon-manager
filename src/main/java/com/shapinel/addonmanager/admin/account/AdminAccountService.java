package com.shapinel.addonmanager.admin.account;

import com.shapinel.addonmanager.admin.account.domain.response.AdminAccountResponses.AccountResponse;
import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.AccountEntity;
import com.shapinel.addonmanager.common.domain.entity.AccountEntity.Role;
import com.shapinel.addonmanager.common.domain.entity.AccountEntity.Status;
import com.shapinel.addonmanager.common.domain.repository.AccountRepository;
import com.shapinel.addonmanager.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class AdminAccountService {

    private final AdminAccountMapper mapper;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccounts(Status status) {
        return mapper.selectAccounts(status == null ? null : status.name()).stream()
                .map(d -> new AccountResponse(d.getId(), d.getLoginId(), d.getName(),
                        d.getRole(), d.getStatus(), d.getCreatedAt()))
                .toList();
    }

    public void approve(Long accountId) {
        changeStatus(accountId, Status.ACTIVE);
    }

    public void reject(Long accountId) {
        changeStatus(accountId, Status.REJECTED);
    }

    private void changeStatus(Long accountId, Status status) {
        AccountEntity account = accountRepository.findById(accountId)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // 관리자 계정의 상태를 실수로 바꿔 잠기는 일을 막는다
        if (account.getRole() == Role.ADMIN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "관리자 계정은 변경할 수 없습니다.");
        }
        account.changeStatus(status);
    }
}
