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
        /** 마지막으로 파일이 올라온 시각(ISO). 모르면 프로젝트 수정 시각 */
        private String lastUpdated;
        private long thumbsUpCount;
        private List<String> categories;
        /** 관리가 중단됐거나 더 이상 제공되지 않는 애드온이면 이유. 아니면 null */
        private String warning;
    }

    @Getter
    @AllArgsConstructor
    public static class CategoryResponse {
        private Long id;
        private String name;
    }

    @Getter
    @AllArgsConstructor
    public static class ScreenshotResponse {
        private String title;
        private String thumbnailUrl;
        private String url;
    }

    @Getter
    @AllArgsConstructor
    public static class AuthorResponse {
        private String name;
        private String url;
    }

    /** 상세 화면용 프로젝트 정보. description 은 HTML 을 지운 글자다. */
    @Getter
    @AllArgsConstructor
    public static class AddonDetailResponse {
        private Long projectId;
        private String name;
        private String summary;
        private String description;
        private String logoUrl;
        private List<ScreenshotResponse> screenshots;
        private List<String> categories;
        private List<AuthorResponse> authors;
        private String websiteUrl;
        private String wikiUrl;
        private String issuesUrl;
        private String sourceUrl;
        private long downloadCount;
        private long thumbsUpCount;
        private String license;
        private String dateCreated;
        private String lastUpdated;
        private String warning;
        private boolean downloadable;
    }

    /** 프로젝트의 파일 하나. releaseType 은 RELEASE / BETA / ALPHA */
    @Getter
    @AllArgsConstructor
    public static class AddonFileResponse {
        private Long fileId;
        private String displayName;
        private String fileName;
        private String releaseType;
        private String fileDate;
        private long fileLength;
        private long downloadCount;
        private List<String> gameVersions;
        /** 이 파일이 설치하는 폴더 이름들 */
        private List<String> folders;
    }

    @Getter
    @AllArgsConstructor
    public static class ChangelogResponse {
        private String text;
    }

    /**
     * 설치에 필요한 다른 애드온(필수 의존성). latestFileId 가 null 이면 이 게임 버전용 파일이 없어 설치할 수 없다.
     */
    @Getter
    @AllArgsConstructor
    public static class DependencyResponse {
        private Long projectId;
        private String name;
        private Long latestFileId;
        private String latestFileName;
        /** false 면 작성자가 외부 배포를 막아 자동 설치가 불가능하다 */
        private boolean downloadable;
    }

    @Getter
    @AllArgsConstructor
    public static class SearchResponse {
        private List<SearchItemResponse> items;
        private long totalCount;
    }
}
