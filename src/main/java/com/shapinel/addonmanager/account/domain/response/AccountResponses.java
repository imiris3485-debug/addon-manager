package com.shapinel.addonmanager.account.domain.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

public class AccountResponses {

    @Getter
    @AllArgsConstructor
    public static class MyInfoResponse {
        private Long id;
        private String loginId;
        private String name;
        private String role;
    }
}
