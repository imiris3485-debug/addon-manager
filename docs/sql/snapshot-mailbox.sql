-- 보관함(내 애드온 목록 기록)과 우편함(편지) 보강.
-- 이미 setup.sql 을 실행한 DB 에서 한 번 실행한다. init-all.sql 로 새로 만드는 DB 는 이미 포함돼 있어 실행하지 않는다.

-- 내 애드온 목록의 기록. 폴더를 다 지웠거나 와우를 새로 깔아도 예전 목록으로 그대로 설치할 수 있게 서버에 남긴다.
--   AUTO  : 앱이 폴더를 읽을 때 목록이 바뀐 것을 자동으로 남긴 기록(계정당 최근 10개만 유지, 빈 목록은 남기지 않는다)
--   MANUAL: 사용자가 이름을 붙여 보관한 기록(지울 때까지 유지)
CREATE TABLE IF NOT EXISTS addon_snapshot (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    public_id    CHAR(36)      NOT NULL COMMENT '외부 식별자(UUID)',
    account_id   BIGINT        NOT NULL,
    game_version VARCHAR(20)   NULL     COMMENT '기록할 때 게임 환경의 게임 버전',
    source       VARCHAR(10)   NOT NULL COMMENT 'AUTO / MANUAL',
    label        VARCHAR(50)   NULL     COMMENT 'MANUAL 기록의 이름',
    addons       VARCHAR(8000) NOT NULL COMMENT '자료실 애드온 폴더 이름(쉼표 구분, 이름순). 같은 목록인지 비교할 수 있게 정렬해 둔다',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at   DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_addon_snapshot_public_id (public_id),
    KEY idx_addon_snapshot_account (account_id, id),
    CONSTRAINT fk_addon_snapshot_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 편지: 받은 사람이 읽었는지(안 읽은 편지 표시), 편지글 길이 확대
ALTER TABLE addon_setup_target ADD COLUMN IF NOT EXISTS read_at DATETIME NULL COMMENT '받은 사람이 편지를 연 시각';
ALTER TABLE addon_setup MODIFY description VARCHAR(1000) NULL;
