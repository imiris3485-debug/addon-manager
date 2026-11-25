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
