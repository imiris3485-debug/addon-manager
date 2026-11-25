package com.shapinel.addonmanager.setup.domain.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

public class SnapshotResponses {

    /** 보관함의 기록 하나 */
    @Getter
    @AllArgsConstructor
    public static class SnapshotResponse {
        /** 외부 식별자(UUID) */
        private String id;
        /** AUTO(자동 기록) / MANUAL(직접 보관) */
        private String source;
        /** 직접 보관한 기록의 이름(자동 기록은 null) */
        private String label;
        private String gameVersion;
        /** 자료실에 지금 있는 애드온 이름(설치할 수 있는 것) */
        private List<String> addons;
        /** 기록에는 있지만 자료실에서 사라져 설치할 수 없는 애드온 */
        private List<String> missingAddons;
        private LocalDateTime createdAt;
    }
}
