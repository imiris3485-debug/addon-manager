package com.shapinel.addonmanager.auth.domain.param;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

public class AuthParams {

    @Getter
    @Setter
    public static class LoginParam {
        @NotBlank(message = "필수입니다")
        private String loginId;

        @NotBlank(message = "필수입니다")
        private String password;
    }

    @Getter
    @Setter
    public static class SignupParam {
        @NotBlank(message = "필수입니다")
        @Size(min = 4, max = 50, message = "4~50자로 입력해 주세요")
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "영문, 숫자, _ . - 만 사용할 수 있습니다")
        private String loginId;

        // BCrypt 는 72바이트까지만 반영하므로 상한을 둔다
        @NotBlank(message = "필수입니다")
        @Size(min = 8, max = 72, message = "8~72자로 입력해 주세요")
        private String password;

        @NotBlank(message = "필수입니다")
        @Size(max = 50, message = "50자 이하로 입력해 주세요")
        private String name;
    }
}
