package com.shapinel.addonmanager.addon.domain.param;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class AddonParams {

    /** 브라우저가 로컬 AddOns 폴더를 스캔한 결과 */
    @Getter
    @Setter
    public static class ScanParam {
        @NotNull(message = "필수입니다")
        @Size(max = 2000, message = "폴더가 너무 많습니다")
        private List<@Valid ScanEntry> entries;
    }

    @Getter
    @Setter
    public static class ScanEntry {
        @NotBlank(message = "필수입니다")
        @Size(max = 255)
        private String folderName;

        /** .toc 의 ## Version */
        @Size(max = 100)
        private String tocVersion;

        /** .toc 의 ## X-Curse-Project-ID */
        private Long projectId;

        /** 폴더 지문(32비트 부호 없는 정수). 아직 계산하지 않았으면 null */
        private Long fingerprint;
    }

    /** 브라우저가 설치를 마친 뒤 결과를 보고한다. */
    @Getter
    @Setter
    public static class InstallReportParam {
        @NotNull(message = "필수입니다")
        private Long projectId;

        @NotNull(message = "필수입니다")
        private Long fileId;

        @Size(max = 100)
        private String tocVersion;

        @NotEmpty(message = "필수입니다")
        @Size(max = 50, message = "폴더가 너무 많습니다")
        private List<@NotBlank String> folders;
    }
}
