package com.shapinel.addonmanager.curseforge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CurseForge 의 설명·변경 내역(작성자가 쓴 HTML)을 화면에 보여 줄 글자로 바꾼다.
 *
 * <p>보안: HTML 을 걸러서 쓰지 않고 <b>태그를 모두 지운 글자만</b> 돌려준다. 화면(React)은 이 글자를 텍스트로만 그리므로
 * 스크립트나 이벤트 속성이 실행될 여지가 없다. 이 웹은 데스크톱 앱에서 PC 폴더를 다루는 통로가 있어서
 * 외부 사람이 쓴 HTML 을 그대로 넣는 것은 특히 위험하다. 서식(굵게·링크 등)을 잃는 대신 안전을 택했다.
 */
public final class HtmlText {

    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile("(?is)<(script|style)\\b.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>|</(p|div|h[1-6]|li|tr|ul|ol|blockquote|pre)\\s*>");
    private static final Pattern LIST_ITEM = Pattern.compile("(?i)<li\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]{1,6});");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern TRAILING_SPACES = Pattern.compile("[ \\t]+\\n");

    private HtmlText() {
    }

    /** @param maxChars 결과 글자 수 상한. 넘으면 잘라서 말줄임표를 붙인다 */
    public static String toPlainText(String html, int maxChars) {
        if (html == null || html.isBlank()) return "";
        String s = SCRIPT_OR_STYLE.matcher(html).replaceAll("");
        s = COMMENT.matcher(s).replaceAll("");
        s = LIST_ITEM.matcher(s).replaceAll("• ");
        s = LINE_BREAK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        // 태그를 지운 "뒤에" 엔티티를 풀어야 &lt;script&gt; 같은 글자가 다시 태그로 취급되지 않는다.
        // (결과는 글자로만 그려지므로 "<script>" 라는 글자가 보일 뿐 실행되지 않는다)
        s = decodeEntities(s);
        s = TRAILING_SPACES.matcher(s).replaceAll("\n");
        s = BLANK_LINES.matcher(s).replaceAll("\n\n").trim();
        if (s.length() > maxChars) s = s.substring(0, Math.max(0, maxChars - 1)).trim() + "…";
        return s;
    }

    private static String decodeEntities(String s) {
        String named = s.replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&amp;", "&");
        Matcher m = NUMERIC_ENTITY.matcher(named);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(0);
            try {
                int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                // 제어문자와 범위를 벗어난 값은 풀지 않는다
                if (code >= 0x20 && Character.isValidCodePoint(code)) replacement = new String(Character.toChars(code));
            } catch (NumberFormatException ignored) {
                // 그대로 둔다
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
