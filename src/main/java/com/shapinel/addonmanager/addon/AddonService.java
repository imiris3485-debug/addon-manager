package com.shapinel.addonmanager.addon;

import com.shapinel.addonmanager.addon.InstalledAddonStore.Snapshot;
import com.shapinel.addonmanager.addon.domain.param.AddonParams.InstallReportParam;
import com.shapinel.addonmanager.addon.domain.param.AddonParams.ScanEntry;
import com.shapinel.addonmanager.addon.domain.param.AddonParams.ScanParam;
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
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FileIndex;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FingerprintMatch;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Mod;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.ModFile;
import com.shapinel.addonmanager.environment.EnvironmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

        // 1) 폴더 지문으로 프로젝트·설치 파일을 정확히 알아낸다
        Map<Long, FingerprintMatch> byFingerprint = new HashMap<>();
        List<Long> fingerprints = entries.stream().map(ScanEntry::getFingerprint)
                .filter(Objects::nonNull).distinct().toList();
        for (FingerprintMatch m : curseForge.matchFingerprints(fingerprints)) {
            if (m.file() != null && m.file().fileFingerprint() != null) {
                byFingerprint.put(m.file().fileFingerprint(), m);
            }
        }

        // 2) 프로젝트별로 폴더를 묶는다. 지문 일치가 .toc 의 프로젝트 ID 보다 우선한다
        Map<Long, List<ScanEntry>> byProject = new LinkedHashMap<>();
        Map<Long, Long> fileIdByProject = new HashMap<>();
        List<String> unmatched = new ArrayList<>();
        for (ScanEntry e : entries) {
            FingerprintMatch match = e.getFingerprint() == null ? null : byFingerprint.get(e.getFingerprint());
            Long projectId = match != null ? match.id() : e.getProjectId();
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
        Map<Long, InstalledAddonEntity> recorded = store.findByProject(environmentId);
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

    /* ===================== 검색 / 다운로드 ===================== */

    public List<CurseForgeClient.GameVersionGroup> getGameVersions() {
        return curseForge.getGameVersions();
    }

    public SearchResponse search(Long accountId, Long environmentId, String query, int page, int pageSize) {
        GameEnvironmentEntity env = environmentService.getOwned(accountId, environmentId);
        int size = Math.min(Math.max(pageSize, 1), 50);
        CurseForgeClient.SearchPage result = curseForge.search(query, env.getGameVersion(), Math.max(page, 0) * size, size);

        List<SearchItemResponse> items = result.mods().stream().map(m -> {
            Optional<FileIndex> latest = pickLatest(m, env.getGameVersion());
            return new SearchItemResponse(m.id(), m.name(), m.summary(), logoUrl(m),
                    m.downloadCount() == null ? 0 : m.downloadCount(), authors(m), websiteUrl(m),
                    latest.map(FileIndex::fileId).orElse(null), latest.map(FileIndex::filename).orElse(null),
                    isDownloadable(m));
        }).toList();
        return new SearchResponse(items, result.totalCount());
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
        return m.links() == null ? null : m.links().websiteUrl();
    }

    private static String authors(Mod m) {
        return m.authors() == null ? "" : m.authors().stream()
                .map(a -> a.name()).collect(Collectors.joining(", "));
    }
}
