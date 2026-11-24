package com.shapinel.addonmanager.admin.release.domain.param;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class AdminReleaseParams {

    @Getter
    @NoArgsConstructor
    public static class CompleteParam {
        /** 빌드가 만든 latest.yml 내용 */
        @NotBlank
        @Size(max = 8000)
        private String latestYml;
    }
}
