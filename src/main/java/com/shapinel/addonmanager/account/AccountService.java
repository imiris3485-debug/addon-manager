package com.shapinel.addonmanager.account;

import com.shapinel.addonmanager.account.domain.dto.AccountDtos.MyInfoDto;
import com.shapinel.addonmanager.account.domain.response.AccountResponses.MyInfoResponse;
import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class AccountService {

    private final AccountMapper accountMapper;

    @Transactional(readOnly = true)
    public MyInfoResponse getMyInfo(Long userId) {
        MyInfoDto dto = accountMapper.selectMyInfo(userId);
        if (dto == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return new MyInfoResponse(dto.getId(), dto.getLoginId(), dto.getName(), dto.getRole());
    }
}
