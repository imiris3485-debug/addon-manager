package com.shapinel.addonmanager.setup.domain.param;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

public class SnapshotParams {

    /** 앱이 폴더를 읽고 알려 주는 현재 목록(자동 기록). 이름은 서비스가 자료실과 대조한다. */
    @Getter
    @NoArgsConstructor
    public static class AutoParam {
        @NotNull(message = "환경은 필수입니다")
        private Long environmentId;

        @NotNull(message = "애드온 목록은 필수입니다")
        @Size(max = 150, message = "애드온은 150개 이하여야 합니다")
        private List<@NotBlank @Size(max = 100) String> addons;
    }

    /** 이름을 붙여 직접 보관하는 기록 */
    @Getter
    @NoArgsConstructor
    public static class ManualParam {
        @NotNull(message = "환경은 필수입니다")
        private Long environmentId;

        @NotBlank(message = "이름은 필수입니다")
        @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요")
        private String label;

        @NotNull(message = "애드온 목록은 필수입니다")
        @Size(max = 150, message = "애드온은 150개 이하여야 합니다")
        private List<@NotBlank @Size(max = 100) String> addons;
    }
}
