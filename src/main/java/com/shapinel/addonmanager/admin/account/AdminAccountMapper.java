package com.shapinel.addonmanager.admin.account;

import com.shapinel.addonmanager.admin.account.domain.dto.AdminAccountDtos.AccountListDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AdminAccountMapper {

    /** status 가 null 이면 전체 조회 */
    List<AccountListDto> selectAccounts(@Param("status") String status);
}
