-- 애드온 자료실: 관리자가 올린 애드온 폴더의 메타데이터.
-- 항목 하나 = 애드온 폴더 하나. 파일은 서버의 LIBRARY_DIR/<public_id>/data/ 아래에 파일 단위로 저장하고(manifest.txt 에 경로·크기·SHA-256),
-- 로그인한 사용자에게만 내려준다(공개 경로로 서빙하지 않는다).
CREATE TABLE IF NOT EXISTS library_addon (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    public_id    CHAR(36)      NOT NULL COMMENT '외부 식별자(UUID). 순차 PK 를 노출하지 않는다',
    name         VARCHAR(100)  NOT NULL COMMENT '애드온 폴더 이름. 대소문자 구분 없이 유일',
    version      VARCHAR(100)  NULL     COMMENT '.toc 의 ## Version. 화면에 보여 주는 용도',
    content_hash CHAR(64)      NOT NULL COMMENT '폴더 내용의 SHA-256(파일 경로+파일별 해시). 내용이 같으면 올리지 않고, 사용자의 업데이트 판단에 쓴다',
    requires     VARCHAR(1000) NULL     COMMENT '.toc 의 Dependencies/RequiredDeps(쉼표 구분). 같이 설치할 폴더',
    extra_requires VARCHAR(1000) NULL   COMMENT '관리자가 따로 지정한 같이 설치할 폴더(쉼표 구분). .toc 에 없는 의존성용이며 다시 올려도 유지된다',
    file_size    BIGINT        NOT NULL COMMENT '파일 크기의 합(바이트)',
    note         VARCHAR(500)  NULL     COMMENT '관리자 메모(출처 등)',
    uploaded_by  BIGINT        NOT NULL COMMENT '올린 관리자 계정 ID',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at   DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_library_addon_public_id (public_id),
    UNIQUE KEY uk_library_addon_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
