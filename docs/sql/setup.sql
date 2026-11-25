-- 계정별 애드온 설치 기록과 세팅(애드온 묶음) 공유.
-- 설치 현황은 앱이 설치·삭제할 때 서버에 알려서 쌓는다. 애드온은 자료실의 폴더 이름(name)으로 가리킨다
-- (자료실에서 지워졌다 다시 올라와도 기록이 끊기지 않게 library_addon 의 FK 를 쓰지 않는다).

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
