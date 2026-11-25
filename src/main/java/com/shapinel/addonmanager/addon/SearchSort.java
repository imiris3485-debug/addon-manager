package com.shapinel.addonmanager.addon;

import lombok.Getter;

/**
 * 검색 정렬. 화면이 CurseForge 의 숫자 코드를 직접 보내지 않고 이 이름만 보내게 해서,
 * 허용한 정렬만 서버가 CurseForge 에 요청한다.
 */
@Getter
public enum SearchSort {
    POPULARITY(2, "desc"),     // 인기
    UPDATED(3, "desc"),        // 최근 갱신
    DOWNLOADS(6, "desc"),      // 다운로드 수
    NAME(4, "asc"),            // 이름
    RATING(12, "desc");        // 평점

    private final int curseForgeField;
    private final String order;

    SearchSort(int curseForgeField, String order) {
        this.curseForgeField = curseForgeField;
        this.order = order;
    }

    /** 비어 있으면 인기순. 알 수 없는 값은 400(잘못된 요청)이 된다. */
    public static SearchSort from(String value) {
        if (value == null || value.isBlank()) return POPULARITY;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 정렬입니다: " + value);
        }
    }
}
