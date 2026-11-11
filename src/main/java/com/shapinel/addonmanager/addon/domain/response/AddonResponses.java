package com.shapinel.addonmanager.addon.domain.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

public class AddonResponses {

    /**
     * UP_TO_DATE      : 최신
     * UPDATE_AVAILABLE: 새 파일 있음
     * UNKNOWN         : 프로젝트는 알지만 설치된 파일을 알 수 없음(지문 확인 필요)
     * NO_FILE         : 이 환경의 게임 버전용 파일이 없음
     */
    public enum UpdateStatus { UP_TO_DATE, UPDATE_AVAILABLE, UNKNOWN, NO_FILE }

    @Getter
    @AllArgsConstructor
    public static class InstalledAddonResponse {
        private Long projectId;
        private String name;
        private String logoUrl;
        private List<String> folders;
        private String installedVersion;
        private Long installedFileId;
        private Long latestFileId;
        private String latestFileName;
        private UpdateStatus status;
        /** false 면 작성자가 외부 배포를 막아 서버 다운로드가 불가능하다 */
        private boolean downloadable;
        private String websiteUrl;
    }

    @Getter
    @AllArgsConstructor
    public static class ScanResponse {
        private List<InstalledAddonResponse> addons;
        /** CurseForge 와 연결하지 못한 폴더 */
        private List<String> unmatchedFolders;
    }

    @Getter
    @AllArgsConstructor
    public static class SearchItemResponse {
        private Long projectId;
        private String name;
        private String summary;
        private String logoUrl;
        private long downloadCount;
        private String authors;
        private String websiteUrl;
        private Long latestFileId;
        private String latestFileName;
        private boolean downloadable;
    }

    @Getter
    @AllArgsConstructor
    public static class SearchResponse {
        private List<SearchItemResponse> items;
        private long totalCount;
    }
}
