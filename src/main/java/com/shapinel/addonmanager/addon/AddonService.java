package com.shapinel.addonmanager.addon;

import com.shapinel.addonmanager.addon.InstalledAddonStore.Snapshot;
import com.shapinel.addonmanager.addon.domain.param.AddonParams.InstallReportParam;
import com.shapinel.addonmanager.addon.domain.param.AddonParams.ScanEntry;
import com.shapinel.addonmanager.addon.domain.param.AddonParams.ScanParam;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.AddonDetailResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.AddonFileResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.AuthorResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.CategoryResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.ChangelogResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.DependencyResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.ScreenshotResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.InstalledAddonResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.ScanResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.SearchItemResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.SearchResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.UpdateStatus;
import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.GameEnvironmentEntity;
import com.shapinel.addonmanager.common.domain.entity.InstalledAddonEntity;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.curseforge.CurseForgeClient;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Category;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Dependency;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FileIndex;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FileModule;
import com.shapinel.addonmanager.curseforge.HtmlText;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FingerprintMatch;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Mod;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.ModFile;
import com.shapinel.addonmanager.environment.EnvironmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 트랜잭션은 InstalledAddonStore / EnvironmentService 안에서만 잡는다.
 * 이 클래스는 CurseForge 외부 호출이 많으므로 DB 연결을 붙잡지 않게 트랜잭션 밖에 둔다.
 */
@Service
@RequiredArgsConstructor
public class AddonService {

    public record DownloadResult(String fileName, byte[] bytes) {
    }

    /** 화면에 보내는 글(설명·변경 내역) 길이 상한 */
    private static final int MAX_TEXT_CHARS = 20_000;
    private static final int MAX_SCREENSHOTS = 12;
    private static final int MAX_CATEGORIES = 6;
    private static final int MAX_FILES = 50;

    private final EnvironmentService environmentService;
    private final InstalledAddonStore store;
    private final CurseForgeClient curseForge;

    /* ===================== 스캔 → 업데이트 판정 ===================== */

    public ScanResponse scan(Long accountId, Long environmentId, ScanParam param) {
        GameEnvironmentEntity env = environmentService.getOwned(accountId, environmentId);

        List<ScanEntry> entries = param.getEntries().stream()
                .filter(e -> isSafeFolderName(e.getFolderName()))
                .sorted(Comparator.comparing(ScanEntry::getFolderName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        // 이 앱이 설치하며 남긴 기록. 폴더 이름으로 프로젝트를 알아내는 보조 수단으로도 쓴다
        Map<Long, InstalledAddonEntity> recorded = store.findByProject(environmentId);
        Map<String, Long> projectByRecordedFolder = recordedFolders(recorded);

        // 1) 폴더 지문으로 프로젝트·설치 파일을 정확히 알아낸다
        Map<Long, FingerprintMatch> byFingerprint = new HashMap<>();
        List<Long> fingerprints = entries.stream().map(ScanEntry::getFingerprint)
                .filter(Objects::nonNull).distinct().toList();
        for (FingerprintMatch m : curseForge.matchFingerprints(fingerprints)) {
            if (m.file() != null && m.file().fileFingerprint() != null) {
                byFingerprint.put(m.file().fileFingerprint(), m);
            }
        }

        // 2) 프로젝트별로 폴더를 묶는다. 우선순위: 지문 일치 > .toc 의 프로젝트 ID > 이 앱의 설치 기록(폴더 이름)
        Map<Long, List<ScanEntry>> byProject = new LinkedHashMap<>();
        Map<Long, Long> fileIdByProject = new HashMap<>();
        List<String> unmatched = new ArrayList<>();
        for (ScanEntry e : entries) {
            FingerprintMatch match = e.getFingerprint() == null ? null : byFingerprint.get(e.getFingerprint());
            Long projectId = match != null ? match.id() : e.getProjectId();
            if (projectId == null) {
                // .toc 에 프로젝트 ID 가 없고 지문도 맞지 않아도, 이 앱이 설치한 폴더면 기록으로 알아본다.
                // 기록을 쓰지 않으면 아래 스냅샷 교체가 설치 기록을 지워 버려 설치한 애드온을 다시 못 알아본다.
                projectId = projectByRecordedFolder.get(e.getFolderName().toLowerCase());
            }
            if (projectId == null) {
                unmatched.add(e.getFolderName());
                continue;
            }
            byProject.computeIfAbsent(projectId, k -> new ArrayList<>()).add(e);
            if (match != null && match.file() != null) {
                fileIdByProject.putIfAbsent(projectId, match.file().id());
            }
        }

        // 3) 최신 파일 판단
        Map<Long, Mod> mods = curseForge.getMods(new ArrayList<>(byProject.keySet()));

        List<InstalledAddonResponse> addons = new ArrayList<>();
        List<Snapshot> snapshots = new ArrayList<>();
        for (Map.Entry<Long, List<ScanEntry>> g : byProject.entrySet()) {
            Long projectId = g.getKey();
            List<ScanEntry> folders = g.getValue();
            Mod mod = mods.get(projectId);
            if (mod == null) {                       // CurseForge 에 없는 프로젝트 ID
                folders.forEach(f -> unmatched.add(f.getFolderName()));
                continue;
            }

            String tocVersion = firstTocVersion(folders);
            Long installedFileId = fileIdByProject.get(projectId);
            if (installedFileId == null) {
                // 지문으로 모를 때는 이 앱이 설치하며 기록한 파일 ID 를 쓰되, .toc 버전이 그대로일 때만 믿는다
                InstalledAddonEntity rec = recorded.get(projectId);
                if (rec != null && Objects.equals(rec.getTocVersion(), tocVersion)) {
                    installedFileId = rec.getInstalledFileId();
                }
            }

            Optional<FileIndex> latest = pickLatest(mod, env.getGameVersion());
            UpdateStatus status = judge(installedFileId, latest);
            List<String> folderNames = folders.stream().map(ScanEntry::getFolderName).toList();

            addons.add(new InstalledAddonResponse(projectId, mod.name(), logoUrl(mod), folderNames, tocVersion,
                    installedFileId, latest.map(FileIndex::fileId).orElse(null),
                    latest.map(FileIndex::filename).orElse(null), status, isDownloadable(mod), websiteUrl(mod)));
            snapshots.add(new Snapshot(projectId, installedFileId, tocVersion, folderNames));
        }

        // 4) 스냅샷 저장 (다른 기기에서도 이어 쓸 수 있도록 마지막 스캔 결과를 남긴다)
        store.replaceSnapshot(environmentId, snapshots);

        addons.sort(Comparator.comparing(InstalledAddonResponse::getName, String.CASE_INSENSITIVE_ORDER));
        return new ScanResponse(addons, unmatched);
    }

    /** 폴더 이름(소문자) → 이 앱이 그 폴더를 설치한 프로젝트. 같은 폴더가 여러 기록에 있으면 먼저 본 것을 쓴다. */
    private static Map<String, Long> recordedFolders(Map<Long, InstalledAddonEntity> recorded) {
        Map<String, Long> map = new HashMap<>();
        recorded.values().forEach(r -> {
            for (String folder : r.getFolders().split(",")) {
                if (!folder.isBlank()) map.putIfAbsent(folder.trim().toLowerCase(), r.getProjectId());
            }
        });
        return map;
    }

    private static UpdateStatus judge(Long installedFileId, Optional<FileIndex> latest) {
        if (latest.isEmpty()) return UpdateStatus.NO_FILE;
        if (installedFileId == null) return UpdateStatus.UNKNOWN;
        return installedFileId < latest.get().fileId() ? UpdateStatus.UPDATE_AVAILABLE : UpdateStatus.UP_TO_DATE;
    }

    /** 정렬한 폴더 중 처음으로 .toc 버전이 있는 값. 설치 보고 시 클라이언트도 같은 규칙을 쓴다. */
    private static String firstTocVersion(List<ScanEntry> sortedFolders) {
        return sortedFolders.stream().map(ScanEntry::getTocVersion)
                .filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

    /**
     * 환경의 게임 버전에 맞는 최신 파일. 안정(release) 파일을 우선하고, 그 안에서 가장 새 파일을 고른다.
     */
    static Optional<FileIndex> pickLatest(Mod mod, String gameVersion) {
        if (mod.latestFilesIndexes() == null) return Optional.empty();
        List<FileIndex> matching = mod.latestFilesIndexes().stream()
                .filter(i -> i.fileId() != null)
                .filter(i -> gameVersion == null || gameVersion.isBlank() || gameVersion.equals(i.gameVersion()))
                .toList();
        int bestRelease = matching.stream().mapToInt(i -> i.releaseType() == null ? 1 : i.releaseType()).min().orElse(1);
        return matching.stream()
                .filter(i -> (i.releaseType() == null ? 1 : i.releaseType()) == bestRelease)
                .max(Comparator.comparing(FileIndex::fileId));
    }

    /* ===================== 설치 보고 / 제거 ===================== */

    public void reportInstalled(Long accountId, Long environmentId, InstallReportParam param) {
        environmentService.getOwned(accountId, environmentId);
        List<String> folders = param.getFolders().stream().filter(AddonService::isSafeFolderName).toList();
        if (folders.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "올바른 폴더명이 없습니다.");
        }
        store.upsert(environmentId, new Snapshot(param.getProjectId(), param.getFileId(), param.getTocVersion(), folders));
    }

    public void removeInstalled(Long accountId, Long environmentId, Long projectId) {
        environmentService.getOwned(accountId, environmentId);
        store.remove(environmentId, projectId);
    }

    /**
     * 이 환경에 설치된 것으로 기록된 프로젝트 ID. 검색 화면의 "설치됨" 표시에 쓴다.
     * 스캔 결과(화면 캐시)와 달리 설치 직후에도 바로 반영된다.
     */
    public List<Long> getInstalledProjectIds(Long accountId, Long environmentId) {
        environmentService.getOwned(accountId, environmentId);
        return store.findByProject(environmentId).keySet().stream().sorted().toList();
    }

    /* ===================== 필수 의존성 ===================== */

    /** 한 번에 따라가는 의존성 수의 상한. 잘못된 데이터로 호출이 끝없이 늘어나는 것을 막는다. */
    static final int MAX_DEPENDENCIES = 20;

    /**
     * 파일이 필요로 하는 다른 애드온(필수 의존성)을 의존성의 의존성까지 모은다. CurseForge 앱이 하는 것과 같다.
     * 선택 의존성·내장 라이브러리는 받지 않는다. 결과는 가까운 것부터이므로 설치는 뒤에서부터 하면 된다.
     * 이 환경의 게임 버전에 맞는 최신 파일을 고르고, 그런 파일이 없으면 latestFileId 를 null 로 알린다.
     */
    public List<DependencyResponse> getRequiredDependencies(Long accountId, Long environmentId, Long projectId, Long fileId) {
        GameEnvironmentEntity env = environmentService.getOwned(accountId, environmentId);

        List<DependencyResponse> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(projectId);
        Deque<ModFile> queue = new ArrayDeque<>();
        queue.add(curseForge.getFile(projectId, fileId));

        while (!queue.isEmpty() && result.size() < MAX_DEPENDENCIES) {
            ModFile file = queue.poll();
            List<Long> required = requiredDependencyIds(file).stream().filter(visited::add).toList();
            if (required.isEmpty()) continue;

            Map<Long, Mod> mods = curseForge.getMods(required);
            for (Long modId : required) {
                if (result.size() >= MAX_DEPENDENCIES) break;
                Mod mod = mods.get(modId);
                if (mod == null) continue;                       // CurseForge 에서 사라진 프로젝트
                Optional<FileIndex> latest = pickLatest(mod, env.getGameVersion());
                boolean downloadable = isDownloadable(mod);
                result.add(new DependencyResponse(modId, mod.name(), latest.map(FileIndex::fileId).orElse(null),
                        latest.map(FileIndex::filename).orElse(null), downloadable));
                // 받을 수 있는 것만 그 안의 의존성을 이어서 확인한다
                if (latest.isPresent() && downloadable) {
                    queue.add(curseForge.getFile(modId, latest.get().fileId()));
                }
            }
        }
        return result;
    }

    private static List<Long> requiredDependencyIds(ModFile file) {
        if (file.dependencies() == null) return List.of();
        return file.dependencies().stream()
                .filter(d -> d.modId() != null && Objects.equals(d.relationType(), Dependency.REQUIRED))
                .map(Dependency::modId)
                .distinct()
                .toList();
    }

    /* ===================== 검색 / 다운로드 ===================== */

    public List<CurseForgeClient.GameVersionGroup> getGameVersions() {
        return curseForge.getGameVersions();
    }

    public SearchResponse search(Long accountId, Long environmentId, String query, int page, int pageSize,
                                 String sort, Long categoryId) {
        GameEnvironmentEntity env = environmentService.getOwned(accountId, environmentId);
        int size = Math.min(Math.max(pageSize, 1), 50);
        SearchSort order = SearchSort.from(sort);
        CurseForgeClient.SearchPage result = curseForge.search(query, env.getGameVersion(), Math.max(page, 0) * size, size,
                order.getCurseForgeField(), order.getOrder(), categoryId);

        List<SearchItemResponse> items = result.mods().stream().map(m -> {
            Optional<FileIndex> latest = pickLatest(m, env.getGameVersion());
            return new SearchItemResponse(m.id(), m.name(), m.summary(), logoUrl(m),
                    m.downloadCount() == null ? 0 : m.downloadCount(), authors(m), websiteUrl(m),
                    latest.map(FileIndex::fileId).orElse(null), latest.map(FileIndex::filename).orElse(null),
                    isDownloadable(m), lastUpdated(m), m.thumbsUpCount() == null ? 0 : m.thumbsUpCount(),
                    categoryNames(m), warning(m));
        }).toList();
        return new SearchResponse(items, result.totalCount());
    }

    /** 검색 필터용 분류 목록. */
    public List<CategoryResponse> getCategories() {
        return curseForge.getCategories().stream()
                // 최상위 분류(애드온 종류 자체)는 필터로 의미가 없어 뺀다
                .filter(c -> c.id() != null && c.name() != null && !Boolean.TRUE.equals(c.isClass()))
                .map(c -> new CategoryResponse(c.id(), c.name()))
                .sorted(Comparator.comparing(CategoryResponse::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** 상세 화면용 정보. 설명은 HTML 을 지운 글자로 준다(CurseForge 조회가 실패해도 나머지는 보여 준다). */
    public AddonDetailResponse getDetail(Long accountId, Long environmentId, Long projectId) {
        environmentService.getOwned(accountId, environmentId);
        Mod m = curseForge.getMods(List.of(projectId)).get(projectId);
        if (m == null) throw new BusinessException(ErrorCode.NOT_FOUND, "CurseForge 에서 찾을 수 없는 애드온입니다.");

        String description;
        try {
            description = HtmlText.toPlainText(curseForge.getDescription(projectId), MAX_TEXT_CHARS);
        } catch (BusinessException e) {
            description = "";
        }
        List<ScreenshotResponse> screenshots = m.screenshots() == null ? List.of() : m.screenshots().stream()
                .map(s -> new ScreenshotResponse(s.title(), safeUrl(s.thumbnailUrl()), safeUrl(s.url())))
                .filter(s -> s.getUrl() != null)
                .limit(MAX_SCREENSHOTS)
                .toList();
        List<AuthorResponse> authors = m.authors() == null ? List.of() : m.authors().stream()
                .map(a -> new AuthorResponse(a.name(), safeUrl(a.url()))).toList();
        var links = m.links();
        return new AddonDetailResponse(m.id(), m.name(), m.summary(), description, logoUrl(m), screenshots,
                categoryNames(m), authors, websiteUrl(m),
                links == null ? null : safeUrl(links.wikiUrl()), links == null ? null : safeUrl(links.issuesUrl()),
                links == null ? null : safeUrl(links.sourceUrl()),
                m.downloadCount() == null ? 0 : m.downloadCount(), m.thumbsUpCount() == null ? 0 : m.thumbsUpCount(),
                m.licenseType(), m.dateCreated(), lastUpdated(m), warning(m), isDownloadable(m));
    }

    /** 이 환경의 게임 버전용 파일 목록(새 것부터). 정식·베타·알파를 모두 보여 줘서 골라 설치할 수 있게 한다. */
    public List<AddonFileResponse> getFiles(Long accountId, Long environmentId, Long projectId) {
        GameEnvironmentEntity env = environmentService.getOwned(accountId, environmentId);
        return curseForge.getFiles(projectId, env.getGameVersion(), MAX_FILES).stream()
                .filter(f -> f.id() != null)
                .sorted(Comparator.comparing((ModFile f) -> f.fileDate() == null ? "" : f.fileDate()).reversed())
                .map(f -> new AddonFileResponse(f.id(), f.displayName(), f.fileName(), releaseTypeName(f.releaseType()),
                        f.fileDate(), f.fileLength() == null ? 0 : f.fileLength(),
                        f.downloadCount() == null ? 0 : f.downloadCount(),
                        f.gameVersions() == null ? List.of() : f.gameVersions(),
                        f.modules() == null ? List.of() : f.modules().stream().map(FileModule::name)
                                .filter(Objects::nonNull).toList()))
                .toList();
    }

    /** 파일의 변경 내역(HTML 을 지운 글자). */
    public ChangelogResponse getChangelog(Long projectId, Long fileId) {
        return new ChangelogResponse(HtmlText.toPlainText(curseForge.getChangelog(projectId, fileId), MAX_TEXT_CHARS));
    }

    public DownloadResult download(Long projectId, Long fileId) {
        ModFile file = curseForge.getFile(projectId, fileId);
        if (file.downloadUrl() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "작성자가 외부 배포를 허용하지 않은 파일입니다. CurseForge 에서 직접 받아 설치해 주세요.");
        }
        return new DownloadResult(file.fileName(), curseForge.download(file.downloadUrl()));
    }

    /* ===================== 공통 ===================== */

    /** 폴더명은 단일 디렉터리 이름이어야 한다(경로 조작 방지). */
    static boolean isSafeFolderName(String name) {
        return name != null && !name.isBlank() && !name.equals(".") && !name.equals("..")
                && !name.contains("/") && !name.contains("\\") && !name.contains(":") && !name.contains(",");
    }

    private static boolean isDownloadable(Mod m) {
        return !Boolean.FALSE.equals(m.allowModDistribution());
    }

    private static String logoUrl(Mod m) {
        return m.logo() == null ? null : m.logo().thumbnailUrl();
    }

    private static String websiteUrl(Mod m) {
        return m.links() == null ? null : safeUrl(m.links().websiteUrl());
    }

    private static String authors(Mod m) {
        return m.authors() == null ? "" : m.authors().stream()
                .map(a -> a.name()).collect(Collectors.joining(", "));
    }

    /** 마지막으로 파일이 올라온 시각. 없으면 프로젝트 수정 시각. */
    private static String lastUpdated(Mod m) {
        return m.dateLastReleasedFile() != null ? m.dateLastReleasedFile() : m.dateModified();
    }

    private static List<String> categoryNames(Mod m) {
        return m.categories() == null ? List.of() : m.categories().stream()
                .map(Category::name).filter(Objects::nonNull).limit(MAX_CATEGORIES).toList();
    }

    /** 사용자가 설치를 망설일 만한 상태(관리 중단·삭제)를 알린다. status: 7=Inactive, 8=Abandoned, 9=Deleted */
    static String warning(Mod m) {
        if (Boolean.FALSE.equals(m.isAvailable()) || Objects.equals(m.status(), 9)) {
            return "CurseForge 에서 더 이상 제공되지 않는 애드온입니다.";
        }
        if (Objects.equals(m.status(), 7) || Objects.equals(m.status(), 8)) {
            return "작성자가 관리를 중단한 애드온입니다. 최신 게임 버전과 맞지 않을 수 있습니다.";
        }
        return null;
    }

    private static String releaseTypeName(Integer type) {
        return switch (type == null ? 1 : type) {
            case 2 -> "BETA";
            case 3 -> "ALPHA";
            default -> "RELEASE";
        };
    }

    /**
     * CurseForge 가 준 주소는 https 만 화면에 내려준다. 다른 값(javascript: 등)이 링크나 이미지에 들어가
     * 실행되는 일을 막기 위해서다.
     */
    static String safeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? uri.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
