package com.shapinel.addonmanager.admin.account.domain.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

public class AdminAccountDtos {

    /** 매퍼 조회 결과 */
    @Getter
    @Setter
    public static class AccountListDto {
        private Long id;
        private String loginId;
        private String name;
        private String role;
        private String status;
        private LocalDateTime createdAt;
    }
}
