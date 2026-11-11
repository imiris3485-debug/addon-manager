-- ============================================================
-- addon-manager 초기화 (한 번에 실행)
-- DB 생성 → 앱 계정 → account → game_environment → installed_addon
-- 이미 테이블이 있으면 건드리지 않는다(IF NOT EXISTS). 컬럼 변경은 별도 ALTER 가 필요하다.
-- ============================================================

CREATE DATABASE IF NOT EXISTS addon_manager
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE addon_manager;

-- 앱 전용 계정. docker-compose 의 MARIADB_USER 로 이미 만들었다면 아래 3줄은 지워도 된다.
CREATE USER IF NOT EXISTS 'addon_app'@'%' IDENTIFIED BY '여기에_강한_비밀번호';
GRANT SELECT, INSERT, UPDATE, DELETE ON addon_manager.* TO 'addon_app'@'%';
FLUSH PRIVILEGES;

-- 계정: 가입 직후 PENDING, 관리자가 승인(ACTIVE)해야 로그인 가능
CREATE TABLE IF NOT EXISTS account (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    login_id   VARCHAR(50)  NOT NULL,
    password   VARCHAR(100) NOT NULL COMMENT 'BCrypt 해시',
    name       VARCHAR(50)  NOT NULL,
    role       VARCHAR(20)  NOT NULL DEFAULT 'USER'    COMMENT 'ADMIN / USER',
    status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACTIVE / REJECTED',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_login_id (login_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 게임 환경: 한 계정이 여러 게임 설치(클라이언트 버전별 AddOns 폴더)를 관리
CREATE TABLE IF NOT EXISTS game_environment (
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

-- 설치 기록: 환경별 설치 스냅샷(실제 기준은 사용자의 AddOns 폴더)
CREATE TABLE IF NOT EXISTS installed_addon (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    environment_id    BIGINT        NOT NULL,
    project_id        BIGINT        NOT NULL COMMENT 'CurseForge 프로젝트(mod) ID',
    installed_file_id BIGINT        NULL     COMMENT '설치된 파일 ID. 알 수 없으면 NULL',
    toc_version       VARCHAR(100)  NULL     COMMENT '설치 당시 .toc Version. 파일 ID 신뢰 여부 판단용',
    folders           VARCHAR(1000) NOT NULL COMMENT '설치된 폴더명 목록(쉼표 구분)',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at        DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_installed_addon (environment_id, project_id),
    CONSTRAINT fk_installed_addon_environment FOREIGN KEY (environment_id) REFERENCES game_environment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
