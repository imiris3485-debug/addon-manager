package com.shapinel.addonmanager.auth.domain.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

public class AuthResponses {

    @Getter
    @AllArgsConstructor
    public static class LoginResponse {
        private String accessToken;
        private long expiresIn;     // 초 단위
    }
}
