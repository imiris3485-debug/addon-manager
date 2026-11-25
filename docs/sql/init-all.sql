-- ============================================================
-- addon-manager 초기화 (한 번에 실행)
-- DB 생성 → 앱 계정 → account → game_environment → library_addon → 설치 기록·세팅 공유
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

-- 애드온 자료실: 관리자가 올린 애드온 폴더(zip)의 메타데이터.
-- 항목 하나 = 애드온 폴더 하나. 파일은 서버의 LIBRARY_DIR 폴더에 <public_id>.zip 으로 저장하고,
-- 로그인한 사용자에게만 내려준다(공개 경로로 서빙하지 않는다).
CREATE TABLE IF NOT EXISTS library_addon (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    public_id    CHAR(36)      NOT NULL COMMENT '외부 식별자(UUID). 순차 PK 를 노출하지 않는다',
    name         VARCHAR(100)  NOT NULL COMMENT '애드온 폴더 이름(zip 의 최상위 폴더). 대소문자 구분 없이 유일',
    version      VARCHAR(100)  NULL     COMMENT '.toc 의 ## Version. 화면에 보여 주는 용도',
    content_hash CHAR(64)      NOT NULL COMMENT '폴더 내용의 SHA-256(파일 경로+파일별 해시). 내용이 같으면 올리지 않고, 사용자의 업데이트 판단에 쓴다',
    requires     VARCHAR(1000) NULL     COMMENT '.toc 의 Dependencies/RequiredDeps(쉼표 구분). 같이 설치할 폴더',
    extra_requires VARCHAR(1000) NULL   COMMENT '관리자가 따로 지정한 같이 설치할 폴더(쉼표 구분). .toc 에 없는 의존성용이며 다시 올려도 유지된다',
    file_size    BIGINT        NOT NULL COMMENT 'zip 크기(바이트)',
    note         VARCHAR(500)  NULL     COMMENT '관리자 메모(출처 등)',
    uploaded_by  BIGINT        NOT NULL COMMENT '올린 관리자 계정 ID',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at   DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_library_addon_public_id (public_id),
    UNIQUE KEY uk_library_addon_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


-- 환경별 현재 설치 현황(애드온당 1행)
CREATE TABLE IF NOT EXISTS account_addon (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    account_id     BIGINT       NOT NULL,
    environment_id BIGINT       NOT NULL,
    addon_name     VARCHAR(100) NOT NULL COMMENT '자료실 애드온 폴더 이름',
    content_hash   CHAR(64)     NOT NULL COMMENT '설치한 시점의 자료실 내용 해시',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at     DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_addon_env_name (environment_id, addon_name),
    KEY idx_account_addon_account (account_id),
    CONSTRAINT fk_account_addon_env FOREIGN KEY (environment_id) REFERENCES game_environment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 설치·업데이트·삭제 이력
CREATE TABLE IF NOT EXISTS addon_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    account_id     BIGINT       NOT NULL,
    environment_id BIGINT       NOT NULL,
    addon_name     VARCHAR(100) NOT NULL,
    action         VARCHAR(20)  NOT NULL COMMENT 'INSTALLED / UPDATED / REMOVED',
    content_hash   CHAR(64)     NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at     DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_addon_event_account (account_id, id),
    CONSTRAINT fk_addon_event_env FOREIGN KEY (environment_id) REFERENCES game_environment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 세팅: 이름 붙인 애드온 묶음. 만든 사람이 지정한 사람에게 공유(제안)할 수 있다.
CREATE TABLE IF NOT EXISTS addon_setup (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)      NOT NULL COMMENT '외부 식별자(UUID)',
    owner_id    BIGINT        NOT NULL COMMENT '만든 계정',
    name        VARCHAR(50)   NOT NULL,
    description VARCHAR(500)  NULL,
    addons      VARCHAR(8000) NOT NULL COMMENT '자료실 애드온 폴더 이름(쉼표 구분)',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_addon_setup_public_id (public_id),
    KEY idx_addon_setup_owner (owner_id),
    CONSTRAINT fk_addon_setup_owner FOREIGN KEY (owner_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 세팅을 받을 사람(공유 대상)
CREATE TABLE IF NOT EXISTS addon_setup_target (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    setup_id   BIGINT   NOT NULL,
    account_id BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_addon_setup_target (setup_id, account_id),
    KEY idx_addon_setup_target_account (account_id),
    CONSTRAINT fk_addon_setup_target_setup FOREIGN KEY (setup_id) REFERENCES addon_setup (id),
    CONSTRAINT fk_addon_setup_target_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
