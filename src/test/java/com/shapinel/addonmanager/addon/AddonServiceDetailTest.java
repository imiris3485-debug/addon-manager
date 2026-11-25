package com.shapinel.addonmanager.addon;

import com.shapinel.addonmanager.addon.domain.response.AddonResponses.AddonDetailResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.AddonFileResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.CategoryResponse;
import com.shapinel.addonmanager.addon.domain.response.AddonResponses.SearchItemResponse;
import com.shapinel.addonmanager.common.code.ErrorCode;
import com.shapinel.addonmanager.common.domain.entity.GameEnvironmentEntity;
import com.shapinel.addonmanager.common.exception.BusinessException;
import com.shapinel.addonmanager.curseforge.CurseForgeClient;
import com.shapinel.addonmanager.curseforge.CurseForgeClient.SearchPage;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Author;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Category;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FileIndex;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.FileModule;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Links;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Mod;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.ModFile;
import com.shapinel.addonmanager.curseforge.CurseForgeDtos.Screenshot;
import com.shapinel.addonmanager.environment.EnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CurseForge 정보 활용 정책
 * - 검색은 허용한 정렬 이름만 받아 CurseForge 정렬 코드로 바꾸고, 분류 필터를 그대로 전달한다.
 * - 외부에서 온 링크·이미지 주소는 https 만 내려준다(javascript: 등으로 화면이 실행되는 것을 막는다).
 * - 설명·변경 내역은 HTML 을 글자로 바꿔 내려주고, 설명 조회가 실패해도 상세 화면은 나머지를 보여 준다.
 * - 관리 중단·삭제된 애드온은 경고를 달고, 파일 목록은 새 것부터 릴리스 종류와 설치 폴더를 알려 준다.
 */
@ExtendWith(MockitoExtension.class)
class AddonServiceDetailTest {

    private static final long ACCOUNT_ID = 1L;
    private static final long ENV_ID = 5L;

    @Mock
    EnvironmentService environmentService;
    @Mock
    InstalledAddonStore store;
    @Mock
    CurseForgeClient curseForge;

    AddonService service;

    @BeforeEach
    void setUp() {
        service = new AddonService(environmentService, store, curseForge);
        // 환경 조회가 필요 없는 테스트(정적 메서드, 변경 내역 등)도 있어서 lenient 로 둔다
        lenient().when(environmentService.getOwned(ACCOUNT_ID, ENV_ID))
                .thenReturn(GameEnvironmentEntity.builder().accountId(ACCOUNT_ID).name("내 PC").gameVersion("1.60.1").build());
    }

    private static Mod mod(long id, Integer status, Boolean isAvailable, List<Screenshot> shots) {
        return new Mod(id, "Questie", "questie", "퀘스트 도우미", null, 1000L, true,
                List.of(new Author("Aero", "https://www.curseforge.com/members/aero"), new Author("Evil", "javascript:alert(1)")),
                new Links("https://www.curseforge.com/wow/addons/questie", "javascript:wiki()", "https://github.com/x/issues", "http://insecure.example/src"),
                List.of(new FileIndex("1.60.1", 900L, "Questie.zip", 1)),
                List.of(new Category(1L, "Quests", false, null, null), new Category(2L, "Map", false, null, null)),
                shots, "2024-01-01T00:00:00Z", "2026-02-02T00:00:00Z", "2026-03-03T00:00:00Z",
                50L, 77L, "MIT", isAvailable, status);
    }

    @Test
    void 검색은_정렬_이름을_CurseForge_코드로_바꾸고_분류를_전달한다() {
        when(curseForge.search("quest", "1.60.1", 0, 20, 3, "desc", 42L)).thenReturn(new SearchPage(List.of(), 0));

        service.search(ACCOUNT_ID, ENV_ID, "quest", 0, 20, "updated", 42L);

        verify(curseForge).search("quest", "1.60.1", 0, 20, 3, "desc", 42L);
    }

    @Test
    void 정렬을_생략하면_인기순이고_이름순은_오름차순이다() {
        when(curseForge.search(null, "1.60.1", 0, 20, 2, "desc", null)).thenReturn(new SearchPage(List.of(), 0));
        when(curseForge.search(null, "1.60.1", 0, 20, 4, "asc", null)).thenReturn(new SearchPage(List.of(), 0));

        service.search(ACCOUNT_ID, ENV_ID, null, 0, 20, null, null);
        service.search(ACCOUNT_ID, ENV_ID, null, 0, 20, "name", null);

        verify(curseForge).search(null, "1.60.1", 0, 20, 2, "desc", null);
        verify(curseForge).search(null, "1.60.1", 0, 20, 4, "asc", null);
    }

    @Test
    void 허용하지_않은_정렬은_거부한다() {
        assertThatThrownBy(() -> service.search(ACCOUNT_ID, ENV_ID, null, 0, 20, "1; DROP", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 검색_결과에_갱신일_추천수_분류_경고가_담긴다() {
        when(curseForge.search(null, "1.60.1", 0, 20, 2, "desc", null))
                .thenReturn(new SearchPage(List.of(mod(10L, 8, true, List.of())), 1));

        SearchItemResponse item = service.search(ACCOUNT_ID, ENV_ID, null, 0, 20, null, null).getItems().get(0);

        assertThat(item.getLastUpdated()).isEqualTo("2026-03-03T00:00:00Z");
        assertThat(item.getThumbsUpCount()).isEqualTo(77L);
        assertThat(item.getCategories()).containsExactly("Quests", "Map");
        assertThat(item.getWarning()).contains("관리를 중단");
        assertThat(item.getWebsiteUrl()).startsWith("https://");
    }

    @Test
    void 상세_정보의_외부_주소는_https_만_남긴다() {
        List<Screenshot> shots = List.of(
                new Screenshot("ok", "", "https://media.forgecdn.net/t.png", "https://media.forgecdn.net/a.png"),
                new Screenshot("bad", "", "javascript:x()", "javascript:alert(1)"),
                new Screenshot("http", "", "http://x.example/t.png", "http://x.example/a.png"));
        when(curseForge.getMods(List.of(10L))).thenReturn(Map.of(10L, mod(10L, 4, true, shots)));
        when(curseForge.getDescription(10L)).thenReturn("<p>소개</p>");

        AddonDetailResponse detail = service.getDetail(ACCOUNT_ID, ENV_ID, 10L);

        assertThat(detail.getScreenshots()).hasSize(1);
        assertThat(detail.getScreenshots().get(0).getUrl()).isEqualTo("https://media.forgecdn.net/a.png");
        assertThat(detail.getWikiUrl()).isNull();                     // javascript:
        assertThat(detail.getSourceUrl()).isNull();                   // http
        assertThat(detail.getIssuesUrl()).isEqualTo("https://github.com/x/issues");
        assertThat(detail.getAuthors()).extracting("url").containsExactly("https://www.curseforge.com/members/aero", null);
    }

    @Test
    void 설명은_HTML_을_지운_글자로_내려준다() {
        when(curseForge.getMods(List.of(10L))).thenReturn(Map.of(10L, mod(10L, 4, true, List.of())));
        when(curseForge.getDescription(10L)).thenReturn("<p>안녕 <script>alert(1)</script><b>세상</b></p>");

        AddonDetailResponse detail = service.getDetail(ACCOUNT_ID, ENV_ID, 10L);

        assertThat(detail.getDescription()).isEqualTo("안녕 세상");
    }

    @Test
    void 설명_조회가_실패해도_상세는_나머지를_돌려준다() {
        when(curseForge.getMods(List.of(10L))).thenReturn(Map.of(10L, mod(10L, 4, true, List.of())));
        when(curseForge.getDescription(10L)).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));

        AddonDetailResponse detail = service.getDetail(ACCOUNT_ID, ENV_ID, 10L);

        assertThat(detail.getDescription()).isEmpty();
        assertThat(detail.getName()).isEqualTo("Questie");
        assertThat(detail.getLicense()).isEqualTo("MIT");
    }

    @Test
    void 없는_프로젝트의_상세는_NOT_FOUND_다() {
        when(curseForge.getMods(List.of(10L))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.getDetail(ACCOUNT_ID, ENV_ID, 10L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void 관리_중단과_삭제된_애드온에는_경고를_단다() {
        assertThat(AddonService.warning(mod(1L, 8, true, List.of()))).contains("관리를 중단");
        assertThat(AddonService.warning(mod(1L, 7, true, List.of()))).contains("관리를 중단");
        assertThat(AddonService.warning(mod(1L, 9, true, List.of()))).contains("더 이상 제공되지");
        assertThat(AddonService.warning(mod(1L, 4, false, List.of()))).contains("더 이상 제공되지");
        assertThat(AddonService.warning(mod(1L, 4, true, List.of()))).isNull();
        assertThat(AddonService.warning(mod(1L, null, null, List.of()))).isNull();
    }

    @Test
    void 외부_주소_검사는_https_와_호스트가_있는_것만_통과시킨다() {
        assertThat(AddonService.safeUrl("https://example.com/a")).isEqualTo("https://example.com/a");
        assertThat(AddonService.safeUrl("HTTPS://example.com")).isNotNull();
        assertThat(AddonService.safeUrl("http://example.com")).isNull();
        assertThat(AddonService.safeUrl("javascript:alert(1)")).isNull();
        assertThat(AddonService.safeUrl("data:text/html,<script>")).isNull();
        assertThat(AddonService.safeUrl("//example.com")).isNull();
        assertThat(AddonService.safeUrl("https://")).isNull();
        assertThat(AddonService.safeUrl("https://bad host/ a")).isNull();
        assertThat(AddonService.safeUrl("  ")).isNull();
        assertThat(AddonService.safeUrl(null)).isNull();
    }

    @Test
    void 파일_목록은_새_것부터_릴리스_종류와_설치_폴더를_알려_준다() {
        ModFile old = new ModFile(1L, 10L, "Q 1.0", "q1.zip", "2025-01-01T00:00:00Z", "u", null, List.of(),
                1, 100L, 5L, List.of("1.60.1"), List.of(new FileModule("Questie", 1L)));
        ModFile beta = new ModFile(2L, 10L, "Q 2.0-beta", "q2.zip", "2026-01-01T00:00:00Z", "u", null, List.of(),
                2, 200L, 6L, List.of("1.60.1"), List.of(new FileModule("Questie", 2L), new FileModule("QuestieTrace", 3L)));
        ModFile alpha = new ModFile(3L, 10L, "Q 3.0-alpha", "q3.zip", "2026-06-01T00:00:00Z", "u", null, List.of(),
                3, 300L, 7L, null, null);
        when(curseForge.getFiles(10L, "1.60.1", 50)).thenReturn(List.of(old, alpha, beta));

        List<AddonFileResponse> files = service.getFiles(ACCOUNT_ID, ENV_ID, 10L);

        assertThat(files).extracting(AddonFileResponse::getFileId).containsExactly(3L, 2L, 1L);
        assertThat(files).extracting(AddonFileResponse::getReleaseType).containsExactly("ALPHA", "BETA", "RELEASE");
        assertThat(files.get(1).getFolders()).containsExactly("Questie", "QuestieTrace");
        assertThat(files.get(0).getFolders()).isEmpty();            // 정보가 없어도 안전하게 빈 목록
        assertThat(files.get(0).getGameVersions()).isEmpty();
    }

    @Test
    void 변경_내역은_HTML_을_지운_글자다() {
        when(curseForge.getChangelog(10L, 2L)).thenReturn("<ul><li>버그 수정</li><li onclick=x()>속도 개선</li></ul>");

        assertThat(service.getChangelog(10L, 2L).getText()).isEqualTo("• 버그 수정\n• 속도 개선");
    }

    @Test
    void 분류_목록은_최상위_분류를_빼고_이름순으로_준다() {
        when(curseForge.getCategories()).thenReturn(List.of(
                new Category(100L, "Addons", true, null, null),
                new Category(2L, "Quests", false, 100L, 100L),
                new Category(3L, "Auction & Economy", false, 100L, 100L),
                new Category(4L, null, false, 100L, 100L)));

        List<CategoryResponse> categories = service.getCategories();

        assertThat(categories).extracting(CategoryResponse::getName).containsExactly("Auction & Economy", "Quests");
    }
}
