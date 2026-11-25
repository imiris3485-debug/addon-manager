package com.shapinel.addonmanager.setup.domain.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

public class SetupResponses {

    /** 세팅 하나. targets 는 만든 사람에게만 채워 준다(받은 사람에게 다른 대상을 알리지 않는다). */
    @Getter
    @AllArgsConstructor
    public static class SetupResponse {
        /** 외부 식별자(UUID) */
        private String id;
        private String name;
        private String description;
        /** 자료실에 지금 있는 애드온 이름 */
        private List<String> addons;
        /** 세팅에는 있지만 자료실에서 지워져 설치할 수 없는 애드온 */
        private List<String> missingAddons;
        private String ownerLoginId;
        private String ownerName;
        /** 내가 만든 세팅인가 */
        private boolean mine;
        private List<PersonResponse> targets;
        private LocalDateTime updatedAt;
    }

    /** 공유 대상 후보·대상 한 명. 고정된 소규모 그룹이라 로그인 ID 로 지정한다(순차 PK 는 내보내지 않는다). */
    @Getter
    @AllArgsConstructor
    public static class PersonResponse {
        private String loginId;
        private String name;
    }

    @Getter
    @AllArgsConstructor
    public static class SetupListResponse {
        /** 내가 만든 세팅 */
        private List<SetupResponse> mine;
        /** 다른 사람이 나에게 공유한 세팅 */
        private List<SetupResponse> received;
    }

    @Getter
    @AllArgsConstructor
    public static class InstalledResponse {
        private String addonName;
        private String contentHash;
        private LocalDateTime updatedAt;
    }

    @Getter
    @AllArgsConstructor
    public static class EventResponse {
        private String addonName;
        /** INSTALLED / UPDATED / REMOVED */
        private String action;
        private LocalDateTime createdAt;
    }
}
