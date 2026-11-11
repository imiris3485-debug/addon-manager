package com.shapinel.addonmanager.account;

import com.shapinel.addonmanager.account.domain.dto.AccountDtos.MyInfoDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountMapper {

    MyInfoDto selectMyInfo(@Param("userId") Long userId);
}
