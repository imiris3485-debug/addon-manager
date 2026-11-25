package com.shapinel.addonmanager.curseforge;

import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Category;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.DataResponse;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FingerprintMatch;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FingerprintResult;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FingerprintsRequest;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.ListResponse;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Mod;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.ModFile;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.ModIdsRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CurseForge Core API 클라이언트. API 키는 서버에만 있고 프론트에는 노출하지 않는다.
 * 호출 횟수를 줄이기 위해 프로젝트 정보를 짧게 캐시한다.
 */
@Slf4j
@Component
public class CurseForgeClient {

    /** WoW 의 CurseForge gameId */
    public static final long WOW_GAME_ID = 1L;
    private static final Duration MOD_CACHE_TTL = Duration.ofMinutes(10);
    private static final int MOD_BATCH_SIZE = 50;

    private record Cached<T>(T value, long expiresAtMillis) {
        boolean valid() {
            return System.currentTimeMillis() < expiresAtMillis;
        }
    }

    private static final int MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024;

    private final RestClient restClient;
    private final RestClient downloadClient;
    private final String apiKey;
    private final Map<Long, Cached<Mod>> modCache = new ConcurrentHashMap<>();
    private volatile Cached<Long> addonClassIdCache;
    private volatile Cached<List<GameVersionGroup>> gameVersionsCache;

    public CurseForgeClient(@Value("${app.curseforge.base-url:https://api.curseforge.com}") String baseUrl,
                            @Value("${app.curseforge.api-key:}") String apiKey) {
        // ⚠️ 외부 호출은 타임아웃을 반드시 둔다(장애 시 스레드 고갈 방지)
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();

        // CDN 다운로드는 리다이렉트를 따라가야 하고 파일이 크므로 읽기 제한을 넉넉히 둔다
        HttpClient dlHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        JdkClientHttpRequestFactory dlFactory = new JdkClientHttpRequestFactory(dlHttp);
        dlFactory.setReadTimeout(Duration.ofSeconds(60));
        this.downloadClient = RestClient.builder().requestFactory(dlFactory).build();
        this.apiKey = apiKey;
    }

    /** 검색 결과 한 페이지. */
    public record SearchPage(List<Mod> mods, long totalCount) {
    }

    /**
     * @param sortField CurseForge 의 ModsSearchSortField(2=인기, 3=최근 갱신, 4=이름, 6=다운로드 수 등)
     * @param sortOrder "asc" 또는 "desc"
     * @param categoryId 분류 필터(null 이면 전체)
     */
    public SearchPage search(String query, String gameVersion, int index, int pageSize,
                             int sortField, String sortOrder, Long categoryId) {
        Long classId = getAddonClassId();
        ListResponse<Mod> res = call(() -> restClient.get()
                .uri(uri -> {
                    var b = uri.path("/v1/mods/search")
                            .queryParam("gameId", WOW_GAME_ID)
                            .queryParam("sortField", sortField)
                            .queryParam("sortOrder", sortOrder)
                            .queryParam("index", index)
                            .queryParam("pageSize", pageSize);
                    if (query != null && !query.isBlank()) b.queryParam("searchFilter", query);
                    if (gameVersion != null && !gameVersion.isBlank()) b.queryParam("gameVersion", gameVersion);
                    if (classId != null) b.queryParam("classId", classId);
                    if (categoryId != null) b.queryParam("categoryId", categoryId);
                    return b.build();
                })
                .header("x-api-key", requireKey())
                .retrieve()
                .body(new ParameterizedTypeReference<ListResponse<Mod>>() {
                }));
        mods(res).forEach(m -> modCache.put(m.id(), new Cached<>(m, expiry())));
        long total = res != null && res.pagination() != null ? res.pagination().totalCount() : 0;
        return new SearchPage(mods(res), total);
    }

    /** 프로젝트 여러 개를 조회한다. 캐시에 없는 것만 묶어서 호출한다. */
    public Map<Long, Mod> getMods(List<Long> ids) {
        Map<Long, Mod> result = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        for (Long id : ids.stream().distinct().toList()) {
            Cached<Mod> c = modCache.get(id);
            if (c != null && c.valid()) {
                result.put(id, c.value());
            } else {
                missing.add(id);
            }
        }
        for (int i = 0; i < missing.size(); i += MOD_BATCH_SIZE) {
            List<Long> batch = missing.subList(i, Math.min(i + MOD_BATCH_SIZE, missing.size()));
            DataResponse<List<Mod>> res = call(() -> restClient.post()
                    .uri("/v1/mods")
                    .header("x-api-key", requireKey())
                    .body(new ModIdsRequest(batch))
                    .retrieve()
                    .body(new ParameterizedTypeReference<DataResponse<List<Mod>>>() {
                    }));
            if (res != null && res.data() != null) {
                for (Mod m : res.data()) {
                    modCache.put(m.id(), new Cached<>(m, expiry()));
                    result.put(m.id(), m);
                }
            }
        }
        return result;
    }

    public ModFile getFile(long modId, long fileId) {
        DataResponse<ModFile> res = call(() -> restClient.get()
                .uri("/v1/mods/{modId}/files/{fileId}", modId, fileId)
                .header("x-api-key", requireKey())
                .retrieve()
                .body(new ParameterizedTypeReference<DataResponse<ModFile>>() {
                }));
        if (res == null || res.data() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "파일을 찾을 수 없습니다.");
        }
        return res.data();
    }

    /**
     * 파일 다운로드. CurseForge 가 내려준 CDN 주소만 허용한다(임의 URL 요청 방지).
     */
    public byte[] download(String downloadUrl) {
        URI uri = URI.create(downloadUrl);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!"https".equals(uri.getScheme()) || !(host.endsWith(".forgecdn.net") || host.equals("forgecdn.net"))) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "허용되지 않은 다운로드 주소입니다.");
        }
        byte[] body = call(() -> downloadClient.get().uri(uri).retrieve().body(byte[].class));
        if (body == null || body.length > MAX_DOWNLOAD_BYTES) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "다운로드 파일이 비어 있거나 너무 큽니다.");
        }
        return body;
    }

    /** 폴더 지문으로 어떤 프로젝트·파일인지 조회한다. */
    public List<FingerprintMatch> matchFingerprints(List<Long> fingerprints) {
        if (fingerprints.isEmpty()) return List.of();
        DataResponse<FingerprintResult> res = call(() -> restClient.post()
                .uri("/v1/fingerprints/{gameId}", WOW_GAME_ID)
                .header("x-api-key", requireKey())
                .body(new FingerprintsRequest(fingerprints))
                .retrieve()
                .body(new ParameterizedTypeReference<DataResponse<FingerprintResult>>() {
                }));
        if (res == null || res.data() == null || res.data().exactMatches() == null) return List.of();
        return res.data().exactMatches();
    }

    /** 게임 버전 종류별 버전 목록(최신순). */
    public record GameVersionGroup(String typeName, List<String> versions) {
    }

    /**
     * CurseForge 가 지원하는 WoW 게임 버전 목록. 환경을 만들 때 직접 입력하지 않고 고르게 하려는 용도다.
     * 자주 바뀌지 않으므로 1시간 캐시한다.
     */
    public List<GameVersionGroup> getGameVersions() {
        Cached<List<GameVersionGroup>> c = gameVersionsCache;
        if (c != null && c.valid()) return c.value();

        DataResponse<List<CurseForgeDtos.GameVersionsEntry>> versions = call(() -> restClient.get()
                .uri("/v1/games/{gameId}/versions", WOW_GAME_ID)
                .header("x-api-key", requireKey())
                .retrieve()
                .body(new ParameterizedTypeReference<DataResponse<List<CurseForgeDtos.GameVersionsEntry>>>() {
                }));
        DataResponse<List<CurseForgeDtos.VersionType>> types = call(() -> restClient.get()
                .uri("/v1/games/{gameId}/version-types", WOW_GAME_ID)
                .header("x-api-key", requireKey())
                .retrieve()
                .body(new ParameterizedTypeReference<DataResponse<List<CurseForgeDtos.VersionType>>>() {
                }));

        Map<Integer, String> typeNames = new HashMap<>();
        if (types != null && types.data() != null) {
            types.data().forEach(t -> typeNames.put(t.id(), t.name()));
        }
        List<GameVersionGroup> groups = new ArrayList<>();
        if (versions != null && versions.data() != null) {
            for (CurseForgeDtos.GameVersionsEntry e : versions.data()) {
                if (e.versions() == null || e.versions().isEmpty()) continue;
                List<String> sorted = e.versions().stream().sorted(CurseForgeClient::compareVersionsDesc).toList();
                groups.add(new GameVersionGroup(typeNames.getOrDefault(e.type(), "기타"), sorted));
            }
        }
        // 가장 새로운 버전을 가진 종류가 위로 오게 정렬한다
        groups.sort((a, b) -> compareVersionsDesc(a.versions().get(0), b.versions().get(0)));
        gameVersionsCache = new Cached<>(groups, System.currentTimeMillis() + Duration.ofHours(1).toMillis());
        return groups;
    }

    /** 점으로 구분된 숫자 버전(12.0.5 등)을 최신이 앞에 오도록 비교한다. 숫자가 아닌 부분은 문자열로 비교. */
    static int compareVersionsDesc(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            int cmp;
            if (x.matches("\\d+") && y.matches("\\d+")) {
                cmp = Long.compare(Long.parseLong(y), Long.parseLong(x));
            } else {
                cmp = y.compareTo(x);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    /** 프로젝트의 전체 설명(HTML). 작성자가 쓴 HTML 이므로 화면에 그대로 넣지 않고 호출한 쪽에서 글자로 바꾼다. */
    public String getDescription(long modId) {
        DataResponse<String> res = call(() -> restClient.get()
                .uri("/v1/mods/{modId}/description", modId)
                .header("x-api-key", requireKey())
                .retrieve()
                .body(new ParameterizedTypeReference<DataResponse<String>>() {
                }));
        return res == null || res.data() == null ? "" : res.data();
    }

    /** 파일의 변경 내역(HTML). 설명과 마찬가지로 호출한 쪽에서 글자로 바꾼다. */
    public String getChangelog(long modId, long fileId) {
        DataResponse<String> res = call(() -> restClient.get()
                .uri("/v1/mods/{modId}/files/{fileId}/changelog", modId, fileId)
                .header("x-api-key", requireKey())
                .retrieve()
                .body(new ParameterizedTypeReference<DataResponse<String>>() {
                }));
        return res == null || res.data() == null ? "" : res.data();
    }

    /** 프로젝트의 파일 목록(최대 pageSize 개). gameVersion 이 있으면 그 버전용 파일만. */
    public List<ModFile> getFiles(long modId, String gameVersion, int pageSize) {
        ListResponse<ModFile> res = call(() -> restClient.get()
                .uri(uri -> {
                    var b = uri.path("/v1/mods/{modId}/files").queryParam("pageSize", pageSize);
                    if (gameVersion != null && !gameVersion.isBlank()) b.queryParam("gameVersion", gameVersion);
                    return b.build(modId);
                })
                .header("x-api-key", requireKey())
                .retrieve()
                .body(new ParameterizedTypeReference<ListResponse<ModFile>>() {
                }));
        return res == null || res.data() == null ? List.of() : res.data();
    }

    private volatile Cached<List<Category>> categoriesCache;

    /** 애드온 분류 목록(검색 필터용). 자주 바뀌지 않으므로 1시간 캐시한다. */
    public List<Category> getCategories() {
        Cached<List<Category>> c = categoriesCache;
        if (c != null && c.valid()) return c.value();
        Long classId = getAddonClassId();
        ListResponse<Category> res = call(() -> restClient.get()
                .uri(uri -> {
                    var b = uri.path("/v1/categories").queryParam("gameId", WOW_GAME_ID);
                    if (classId != null) b.queryParam("classId", classId);
                    return b.build();
                })
                .header("x-api-key", requireKey())
                .retrieve()
                .body(new ParameterizedTypeReference<ListResponse<Category>>() {
                }));
        List<Category> list = res == null || res.data() == null ? List.of() : res.data();
        categoriesCache = new Cached<>(list, System.currentTimeMillis() + Duration.ofHours(1).toMillis());
        return list;
    }

    /** WoW 의 "Addons" 분류 ID. 검색에서 애드온만 나오게 하려고 이름으로 찾는다(못 찾으면 필터 생략). */
    private Long getAddonClassId() {
        Cached<Long> c = addonClassIdCache;
        if (c != null && c.valid()) return c.value();
        Long found = null;
        try {
            ListResponse<Category> res = restClient.get()
                    .uri(uri -> uri.path("/v1/categories")
                            .queryParam("gameId", WOW_GAME_ID).queryParam("classesOnly", true).build())
                    .header("x-api-key", requireKey())
                    .retrieve()
                    .body(new ParameterizedTypeReference<ListResponse<Category>>() {
                    });
            if (res != null && res.data() != null) {
                found = res.data().stream()
                        .filter(cat -> cat.name() != null && cat.name().equalsIgnoreCase("Addons"))
                        .map(Category::id).findFirst().orElse(null);
            }
        } catch (RestClientException e) {
            log.warn("CurseForge 분류 조회 실패, 분류 필터 없이 검색합니다: {}", e.getMessage());
        }
        // 실패해도 잠시 캐시해서 매 검색마다 재시도하지 않는다
        addonClassIdCache = new Cached<>(found, System.currentTimeMillis() + Duration.ofHours(1).toMillis());
        return found;
    }

    private String requireKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "CurseForge API 키가 설정되지 않았습니다.");
        }
        return apiKey;
    }

    private static List<Mod> mods(ListResponse<Mod> res) {
        return res == null || res.data() == null ? List.of() : res.data();
    }

    private static long expiry() {
        return System.currentTimeMillis() + MOD_CACHE_TTL.toMillis();
    }

    private <T> T call(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            // API 키 등 요청 상세는 로그/응답에 남기지 않고 원인 메시지만 기록한다
            log.error("CurseForge 호출 실패: {}", e.getMessage());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }
}
