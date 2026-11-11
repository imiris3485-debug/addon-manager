package com.shapinel.addonmanager.account.domain.dto;

import lombok.Getter;
import lombok.Setter;

public class AccountDtos {

    /** 매퍼 조회 결과 */
    @Getter
    @Setter
    public static class MyInfoDto {
        private Long id;
        private String loginId;
        private String name;
        private String role;
    }
}
