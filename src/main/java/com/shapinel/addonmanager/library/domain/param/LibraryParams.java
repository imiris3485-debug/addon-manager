package com.shapinel.addonmanager.library.domain.param;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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

    /** 한 번에 받을 파일들의 경로(폴더 기준 상대 경로). 서버의 파일 목록에 있는 것만 허용된다. */
    @Getter
    @NoArgsConstructor
    public static class BundleParam {
        @NotEmpty(message = "경로가 없습니다")
        @Size(max = 20000, message = "한 번에 받을 수 있는 파일 수를 넘었습니다")
        private List<@NotBlank @Size(max = 240) String> paths;
    }

    /**
     * 올리기 시작. 올릴 애드온의 파일 목록(경로·크기·SHA-256)을 먼저 보낸다.
     * 서버가 목록을 검사하고, 이미 있는 같은 파일은 다시 받지 않도록 "올려야 할 파일"만 돌려준다.
     */
    @Getter
    @NoArgsConstructor
    public static class BeginParam {
        @NotBlank(message = "폴더 이름은 필수입니다")
        @Size(max = 100, message = "폴더 이름이 너무 깁니다")
        private String folder;

        @Size(max = 500, message = "메모는 500자 이하로 입력해 주세요")
        private String note;

        @Valid
        @NotEmpty(message = "파일이 없습니다")
        @Size(max = 20000, message = "파일이 너무 많습니다")
        private List<FileParam> files;
    }

    @Getter
    @NoArgsConstructor
    public static class FileParam {
        /** 애드온 폴더 기준 상대 경로(/ 구분) */
        @NotBlank
        @Size(max = 240)
        private String path;

        @NotNull
        @PositiveOrZero
        private Long size;

        /** 파일 내용의 SHA-256(소문자 16진수 64자) */
        @NotBlank
        @Size(min = 64, max = 64)
        private String sha256;
    }
}
