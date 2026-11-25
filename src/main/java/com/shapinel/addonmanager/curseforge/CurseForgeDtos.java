package com.shapinel.addonmanager.curseforge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * CurseForge Core API 응답 모델. 필요한 필드만 매핑하고 나머지는 무시한다.
 */
public class CurseForgeDtos {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataResponse<T>(T data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListResponse<T>(List<T> data, Pagination pagination) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pagination(int index, int pageSize, int resultCount, long totalCount) {
    }

    /**
     * 프로젝트. status: 1=New … 4=Approved, 7=Inactive, 8=Abandoned, 9=Deleted 등(CurseForge 명세의 ModStatus).
     * 아래쪽 필드는 상세 화면(스크린샷·분류·링크·갱신일·추천수·라이선스)에 쓴다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Mod(Long id, String name, String slug, String summary, Logo logo, Long downloadCount,
                      Boolean allowModDistribution, List<Author> authors, Links links,
                      List<FileIndex> latestFilesIndexes,
                      List<Category> categories, List<Screenshot> screenshots,
                      String dateCreated, String dateModified, String dateLastReleasedFile,
                      Long favoriteCount, Long thumbsUpCount, String licenseType,
                      Boolean isAvailable, Integer status) {

        /** 상세 정보 없이 필수 필드만 아는 경우(주로 테스트)용. */
        public Mod(Long id, String name, String slug, String summary, Logo logo, Long downloadCount,
                   Boolean allowModDistribution, List<Author> authors, Links links, List<FileIndex> latestFilesIndexes) {
            this(id, name, slug, summary, logo, downloadCount, allowModDistribution, authors, links, latestFilesIndexes,
                    null, null, null, null, null, null, null, null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Logo(String thumbnailUrl, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Screenshot(String title, String description, String thumbnailUrl, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(String name, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Links(String websiteUrl, String wikiUrl, String issuesUrl, String sourceUrl) {
    }

    /** 게임 버전별 최신 파일 색인. releaseType: 1=release, 2=beta, 3=alpha */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileIndex(String gameVersion, Long fileId, String filename, Integer releaseType) {
    }

    /**
     * 파일. releaseType: 1=정식, 2=베타, 3=알파. modules 는 이 파일이 설치하는 폴더들(이름·지문)이다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModFile(Long id, Long modId, String displayName, String fileName, String fileDate,
                          String downloadUrl, Long fileFingerprint, List<Dependency> dependencies,
                          Integer releaseType, Long fileLength, Long downloadCount,
                          List<String> gameVersions, List<FileModule> modules) {

        public ModFile(Long id, Long modId, String displayName, String fileName, String fileDate,
                       String downloadUrl, Long fileFingerprint, List<Dependency> dependencies) {
            this(id, modId, displayName, fileName, fileDate, downloadUrl, fileFingerprint, dependencies,
                    null, null, null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileModule(String name, Long fingerprint) {
    }

    /**
     * 파일이 필요로 하는 다른 프로젝트. relationType: 1=포함된 라이브러리, 2=선택, 3=필수, 4=도구, 5=호환 안 됨, 6=포함.
     * CurseForge 앱은 필수(3)를 자동으로 같이 설치한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dependency(Long modId, Integer relationType) {
        public static final int REQUIRED = 3;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FingerprintResult(List<FingerprintMatch> exactMatches) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FingerprintMatch(Long id, ModFile file) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Category(Long id, String name, Boolean isClass, Long parentCategoryId, Long classId) {
    }

    /** 설명·변경 내역처럼 본문이 문자열 하나인 응답 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StringResponse(String data) {
    }

    /** 게임 버전 종류별 버전 목록. type 은 version-types 의 id */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameVersionsEntry(Integer type, List<String> versions) {
    }

    /** 게임 버전 종류(예: Retail, Classic 등) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VersionType(Integer id, String name, String slug) {
    }

    // ----- 요청 본문 -----
    public record ModIdsRequest(List<Long> modIds) {
    }

    public record FingerprintsRequest(List<Long> fingerprints) {
    }
}
