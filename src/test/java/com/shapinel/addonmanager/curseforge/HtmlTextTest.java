package com.shapinel.addonmanager.curseforge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 HTML → 글자 변환 정책
 * - CurseForge 설명·변경 내역은 작성자가 쓴 HTML 이라 신뢰하지 않는다. 태그는 모두 지우고 글자만 남긴다.
 * - 스크립트·스타일 블록은 내용까지 지우고, 이벤트 속성·javascript: 링크는 태그와 함께 사라진다.
 * - 줄바꿈 계열 태그는 줄바꿈으로, 목록은 글머리표로 바꿔 읽기 좋게 한다.
 * - 글자 수 상한을 넘으면 자른다.
 */
class HtmlTextTest {

    @Test
    void 스크립트와_스타일은_내용까지_지운다() {
        String html = "<p>안녕</p><script>alert('x')</script><style>body{display:none}</style><p>하세요</p>";

        String text = HtmlText.toPlainText(html, 1000);

        assertThat(text).contains("안녕").contains("하세요");
        assertThat(text).doesNotContain("alert").doesNotContain("display");
    }

    @Test
    void 대소문자를_섞거나_속성이_있는_스크립트도_지운다() {
        String text = HtmlText.toPlainText("A<ScRiPt type=\"text/javascript\">evil()</sCrIpT>B", 1000);

        assertThat(text).isEqualTo("AB");
    }

    @Test
    void 이벤트_속성과_javascript_링크는_태그째_사라지고_글자만_남는다() {
        String text = HtmlText.toPlainText(
                "<a href=\"javascript:steal()\" onclick=\"steal()\">클릭</a><img src=x onerror=\"steal()\"><div onmouseover=steal()>본문</div>", 1000);

        assertThat(text).contains("클릭").contains("본문");
        assertThat(text).doesNotContain("steal").doesNotContain("javascript").doesNotContain("onerror");
    }

    @Test
    void 쪼개서_숨긴_스크립트_태그도_태그로_남지_않는다() {
        String text = HtmlText.toPlainText("<scr<script>ipt>alert(1)</scr</script>ipt>", 1000);

        assertThat(text).doesNotContain("<").doesNotContain(">");
    }

    @Test
    void 엔티티로_쓴_태그는_태그가_아닌_글자로_남는다() {
        // 화면은 이 값을 텍스트로만 그리므로 "<script>" 라는 글자가 보일 뿐 실행되지 않는다
        String text = HtmlText.toPlainText("사용법: &lt;script&gt; 태그를 쓰지 마세요 &amp; 주의", 1000);

        assertThat(text).isEqualTo("사용법: <script> 태그를 쓰지 마세요 & 주의");
    }

    @Test
    void 숫자_엔티티를_풀고_제어문자는_풀지_않는다() {
        assertThat(HtmlText.toPlainText("&#65;&#x42;&#44032;", 1000)).isEqualTo("AB가");
        assertThat(HtmlText.toPlainText("a&#0;b&#1114112;c", 1000)).doesNotContain("\u0000");
    }

    @Test
    void 줄바꿈_태그는_줄바꿈이_되고_목록은_글머리표가_된다() {
        String text = HtmlText.toPlainText("<h2>제목</h2><p>첫 줄<br>둘째 줄</p><ul><li>하나</li><li>둘</li></ul>", 1000);

        assertThat(text).contains("제목\n").contains("첫 줄\n둘째 줄").contains("• 하나\n• 둘");
    }

    @Test
    void 연속된_빈_줄은_하나로_줄이고_앞뒤_공백을_지운다() {
        String text = HtmlText.toPlainText("<p>A</p><p></p><p></p><p></p><p>B</p>  ", 1000);

        assertThat(text).isEqualTo("A\n\nB");
    }

    @Test
    void 주석은_지운다() {
        assertThat(HtmlText.toPlainText("A<!-- 비밀 메모 -->B", 1000)).isEqualTo("AB");
    }

    @Test
    void 닫히지_않은_태그가_있어도_다음_글자를_삼키지_않는다() {
        String text = HtmlText.toPlainText("<p>시작 <b>굵게 그리고 계속</p>", 1000);

        assertThat(text).contains("시작").contains("굵게 그리고 계속");
    }

    @Test
    void 글자_수_상한을_넘으면_자르고_말줄임표를_붙인다() {
        String text = HtmlText.toPlainText("<p>" + "가".repeat(100) + "</p>", 10);

        assertThat(text).hasSize(10).endsWith("…");
    }

    @Test
    void 비어_있거나_null_이면_빈_글자다() {
        assertThat(HtmlText.toPlainText(null, 100)).isEmpty();
        assertThat(HtmlText.toPlainText("   ", 100)).isEmpty();
        assertThat(HtmlText.toPlainText("<p></p>", 100)).isEmpty();
    }
}
