package com.shapinel.addonmanager.library.domain.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

public class LibraryResponses {

    /** 자료실 항목 하나(애드온 폴더 하나) */
    @Getter
    @AllArgsConstructor
    public static class LibraryAddonResponse {
        /** 외부 식별자(UUID) */
        private String id;
        /** 애드온 폴더 이름 */
        private String name;
        private String version;
        /** 폴더 내용의 해시. 같으면 내용이 같다 */
        private String contentHash;
        /** 같이 설치할 폴더들(.toc 가 요구하는 것 + 관리자가 지정한 것, 중복 제거) */
        private List<String> requires;
        /** 이 중 관리자가 따로 지정한 것(관리 화면에서 고칠 수 있다) */
        private List<String> extraRequires;
        private long fileSize;
        private String note;
        private LocalDateTime updatedAt;
    }
}
