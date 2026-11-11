package com.shapinel.addonmanager.environment.domain.param;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

public class EnvironmentParams {

    @Getter
    @Setter
    public static class EnvironmentSaveParam {
        @NotBlank(message = "필수입니다")
        @Size(max = 50, message = "50자 이하로 입력해 주세요")
        private String name;

        /** CurseForge 게임 버전 필터 값(예: 3.3.5) */
        @NotBlank(message = "필수입니다")
        @Size(max = 20, message = "20자 이하로 입력해 주세요")
        private String gameVersion;
    }
}
