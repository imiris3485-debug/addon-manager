-- 게임 환경: 한 계정이 여러 게임 설치(클라이언트 버전별 AddOns 폴더)를 관리할 수 있다.
CREATE TABLE game_environment (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    account_id   BIGINT       NOT NULL,
    name         VARCHAR(50)  NOT NULL,
    game_version VARCHAR(20)  NOT NULL COMMENT 'CurseForge 게임 버전 필터 값(예: 3.3.5)',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at   DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_game_environment_account (account_id),
    CONSTRAINT fk_game_environment_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 설치 기록: 환경별로 어떤 CurseForge 프로젝트의 어떤 파일을 설치했는지.
-- 실제 기준은 사용자의 AddOns 폴더이며, 이 테이블은 스캔/설치 결과를 저장한 스냅샷이다.
CREATE TABLE installed_addon (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    environment_id    BIGINT       NOT NULL,
    project_id        BIGINT       NOT NULL COMMENT 'CurseForge 프로젝트(mod) ID',
    installed_file_id BIGINT       NULL     COMMENT '설치된 파일 ID. 알 수 없으면 NULL',
    toc_version       VARCHAR(100) NULL     COMMENT '설치 당시 .toc Version. 파일 ID 신뢰 여부 판단용',
    folders           VARCHAR(1000) NOT NULL COMMENT '설치된 폴더명 목록(쉼표 구분)',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at        DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_installed_addon (environment_id, project_id),
    CONSTRAINT fk_installed_addon_environment FOREIGN KEY (environment_id) REFERENCES game_environment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
