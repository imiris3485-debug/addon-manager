package com.shapinel.addonmanager.environment.domain.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

public class EnvironmentResponses {

    @Getter
    @AllArgsConstructor
    public static class EnvironmentResponse {
        private Long id;
        private String name;
        private String gameVersion;
    }
}
