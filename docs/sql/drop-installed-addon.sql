-- CurseForge 연동을 없애면서 설치 기록(installed_addon)을 더는 쓰지 않는다.
-- 이미 만든 DB 에서 한 번 실행한다(없어도 앱은 동작하지만, 남아 있으면 게임 환경 삭제가 FK 때문에 실패할 수 있다).
DROP TABLE IF EXISTS installed_addon;
