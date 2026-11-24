package com.shapinel.addonmanager.admin.release.domain.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

public class AdminReleaseResponses {

    /** 현재 게시된 데스크톱 앱 */
    @Getter
    @AllArgsConstructor
    public static class ReleaseResponse {
        private boolean published;
        private String version;
        private long size;
        private String releaseDate;
    }

    @Getter
    @AllArgsConstructor
    public static class UploadStartResponse {
        private String uploadId;
        /** 한 번에 보낼 수 있는 최대 조각 크기(바이트) */
        private int chunkSize;
        /** 올릴 수 있는 설치 파일의 최대 크기(바이트) */
        private long maxBytes;
    }
}
