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
        /** 파일 크기의 합(바이트) */
        private long fileSize;
        private String note;
        private LocalDateTime updatedAt;
    }

    /** 파일 목록의 한 줄(받는 쪽이 파일별로 내려받고 SHA-256 으로 검증한다) */
    @Getter
    @AllArgsConstructor
    public static class FileResponse {
        private String path;
        private long size;
        private String sha256;
    }

    /** 올리기 시작 결과 */
    @Getter
    @AllArgsConstructor
    public static class BeginResponse {
        /** 이번 올리기의 식별자(서버가 만든 UUID) */
        private String uploadId;
        /** 아직 서버에 없어 올려야 할 파일의 경로. 나머지는 이전 버전에서 그대로 가져왔다 */
        private List<String> needed;
    }

    /** 파일 조각을 올린 결과 */
    @Getter
    @AllArgsConstructor
    public static class ChunkResponse {
        /** 지금까지 받은 바이트 수 */
        private long received;
        /** 파일을 다 받았고 SHA-256 도 맞았는가 */
        private boolean done;
    }
}
