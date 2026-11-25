package com.shapinel.addonmanager.library.domain.param;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

public class LibraryParams {

    /** 관리자가 지정하는 같이 설치할 애드온 폴더 이름들. 비어 있으면 지정을 지운다. */
    @Getter
    @NoArgsConstructor
    public static class RequiresParam {
        @NotNull(message = "필수입니다")
        @Size(max = 30, message = "30개 이하로 지정해 주세요")
        private List<String> requires;
    }
}
