package com.shapinel.addonmanager.admin.account.domain.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

public class AdminAccountResponses {

    @Getter
    @AllArgsConstructor
    public static class AccountResponse {
        private Long id;
        private String loginId;
        private String name;
        private String role;
        private String status;
        private LocalDateTime createdAt;
    }
}
