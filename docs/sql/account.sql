-- 계정 테이블. 공통 컬럼(created_at/updated_at/deleted_at)은 DB 가 채운다.
-- 가입 직후는 PENDING 이고 관리자가 승인(ACTIVE)해야 로그인할 수 있다.
CREATE TABLE account (
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
