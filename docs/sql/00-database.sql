-- 데이터베이스 생성. 이름과 계정/비밀번호는 환경에 맞게 바꾼다.
-- docker-compose 의 MariaDB 이미지는 MARIADB_DATABASE / MARIADB_USER / MARIADB_PASSWORD 로
-- 이미 만들어 주므로, 그 경우 이 파일은 실행하지 않아도 된다.
CREATE DATABASE IF NOT EXISTS addon_manager
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- 앱 전용 계정 (root 로 앱을 연결하지 않는다)
CREATE USER IF NOT EXISTS 'addon_app'@'%' IDENTIFIED BY '여기에_강한_비밀번호';
GRANT SELECT, INSERT, UPDATE, DELETE ON addon_manager.* TO 'addon_app'@'%';
FLUSH PRIVILEGES;
