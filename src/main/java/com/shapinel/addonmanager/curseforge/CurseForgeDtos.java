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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Mod(Long id, String name, String slug, String summary, Logo logo, Long downloadCount,
                      Boolean allowModDistribution, List<Author> authors, Links links,
                      List<FileIndex> latestFilesIndexes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Logo(String thumbnailUrl, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Links(String websiteUrl) {
    }

    /** 게임 버전별 최신 파일 색인. releaseType: 1=release, 2=beta, 3=alpha */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileIndex(String gameVersion, Long fileId, String filename, Integer releaseType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModFile(Long id, Long modId, String displayName, String fileName, String fileDate,
                          String downloadUrl, Long fileFingerprint) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FingerprintResult(List<FingerprintMatch> exactMatches) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FingerprintMatch(Long id, ModFile file) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Category(Long id, String name, Boolean isClass) {
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
