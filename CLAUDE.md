# addon-manager 개발 규칙

## 소통
- 모든 대화와 주석, 커밋 메시지 본문은 한글로 작성한다.

## 스택
- Java 21, Spring Boot 4.1.x, Gradle, MariaDB(MyBatis + JPA), MongoDB, WebSocket(STOMP Simple Broker)
- Spring Security(OAuth2 Resource Server, HS256 JWT). Kafka는 사용하지 않는다.
- 기본 패키지: `com.shapinel.addonmanager`
- 상세 배경은 `docs/프로젝트 구조` 참고.

## 구조
- 기능 단위 패키지: {feature}/XxxController, XxxService, XxxMapper, domain/{param,response,dto}
- DTO는 XxxParams / XxxResponses / XxxDtos 파일에 static 중첩 클래스로 묶는다.
- JPA 엔티티·리포지토리는 common/domain/entity, common/domain/repository 에 모은다.
- MyBatis XML은 resources/mapper/{feature}/{feature}.xml

## 프론트엔드 (frontend/)
- React 19 + Vite + TypeScript + Tailwind v4 + TanStack Query + React Router. 별도 UI 라이브러리 없이 `index.css` 의 `.btn/.input/.card` 사용.
- 개발: 백엔드를 5534 로 띄운 뒤 `cd frontend && npm run dev` (5173, `/api` 는 5534 로 프록시). 빌드 검증: `npm run build`.
- 배포 jar: `./gradlew bootJar` 한 번으로 프론트(npm)까지 빌드해 `build/libs/app.jar` 한 개에 포함한다(Node 필요). Node 없이 백엔드만 빌드하려면 `-PskipFrontend`.
- 루트 `Dockerfile` 은 이미지 안에서 빌드하는 멀티스테이지 방식이다(선택). `src/main/resources/static/` 은 커밋하지 않는다.
- 핵심 로직은 `src/lib/`: `scanner`(폴더의 .toc 읽기) · `hash`(내용 해시) · `installer`(파일 목록 검사·바뀐 파일만 받아 쓰기) · `library`(설치 상태·의존성 순서·설치) · `librarySync`(관리자 원본 폴더 동기화) · `handles`/`nativeFs`(폴더 핸들).
- **내용 해시 규칙(`hash.ts`)은 서버 `AddonZipInspector` 와 같아야 한다.** 한쪽만 바꾸면 "내용이 같은데 다르다"고 판단한다. 바꾸면 양쪽을 함께 바꾸고 실제 폴더로 같은 값이 나오는지 확인한다.
- 운영 배포는 반드시 HTTPS 로 서빙한다.
- 토큰으로 읽은 역할(관리자 여부)은 화면 표시용일 뿐이며, 권한 검사는 서버 `@PreAuthorize` 가 한다.

## 애드온 자료실 (library/)
- **CurseForge 등 외부 서비스와 연동하지 않는다.** 관리자가 애드온을 모아 둔 폴더(원본 폴더)를 데스크톱 앱에서 읽어 서버에 올리고(동기화), 사용자는 자료실 목록에서 골라 설치·업데이트한다.
- 항목 하나 = 애드온 폴더 하나. 내용 해시(`contentHash`)가 같으면 다시 올리지 않고, 사용자는 내 폴더의 실제 내용 해시와 비교해 업데이트를 판단한다.
- **파일 단위 저장**: 애드온을 zip 한 덩어리가 아니라 파일 단위로 올리고(`LibraryUploadService`: begin → 작은 파일은 **묶음**(bundle, 파일 수천 개여도 요청 몇 번) / 큰 파일(2MB 초과)은 chunk(16MB, 이어 올리기) → complete) 서버에 `<id>/data/…` + `manifest.txt`(경로·크기·SHA-256)로 둔다. 그래서 크기 제한 없이 올릴 수 있고(파일 하나당 250MB, 합계 8GB), 이미 있는 같은 파일은 올리지도 받지도 않는다. 받는 쪽은 `GET /library/{id}/manifest` 로 목록을 받고 `POST /library/{id}/bundle` 로 바뀐 파일들을 **한 스트림으로** 받는다(파일마다 요청하면 수천 개짜리 애드온이 너무 느리다). 묶음 형식은 파일마다 [경로 길이 2][경로][크기 8][내용]이고 서버(`LibraryService.writeBundle`/`LibraryUploadService.bundle`)와 프론트(`installer.ts`/`librarySync.ts`)가 같아야 한다. 예전 zip 항목은 서버 시작 때 `LegacyLibraryMigrator` 가 한 번 바꾼다.
- 같이 설치할 애드온 = `.toc` 의 `Dependencies`/`RequiredDeps` + 관리자가 지정한 `extra_requires`(다시 올려도 유지). 실제 애드온은 .toc 에 의존성을 선언하지 않는 경우가 많다(예: Questie → QuestieDB).
- **공개 경로(`/downloads` 등)로 내주지 않는다.** 받기는 인증이 필요한 `/api/v1/library/{id}/download` 로만 한다. 올리기·지우기·의존성 지정은 ADMIN 전용(`AdminLibrarySecurityTest` 가 고정).
- 올라온 파일은 받은 사람들의 게임 폴더에 그대로 쓰이므로 올릴 때 `LibraryUploadService`(검사 함수는 `AddonZipInspector`)가 파일 목록을 검사한다: 경로 조작·Windows 특수 이름·실행 파일/스크립트(허용 확장자 목록만 통과)·같은 경로 중복(대소문자 무시)·파일 수와 크기·.toc 없음. 목록에 없는 경로는 받지 않고, 선언한 크기·SHA-256 과 다른 파일은 버린다. 이 검사를 약화시키는 변경은 하지 않는다. 허용 확장자를 늘릴 때는 실행될 수 있는 형식을 넣지 않는다.
- 받는 쪽(`installer.ts`)도 같은 확장자·경로 검사를 다시 하고, 쓰기 전에 **목록의 해시가 자료실이 알려 준 내용 해시와 같은지**, 파일마다 **내려받은 내용의 크기·SHA-256 이 목록과 같은지** 확인한 뒤에만 쓴다(확인에 실패한 파일은 쓰지 않는다). 허용 확장자 목록은 서버와 프론트가 같아야 한다.
- 저장 폴더 이름은 서버가 만든 UUID 만 쓴다(`<id>/`). 클라이언트가 준 이름·경로는 파일 이름에 쓰지 않는다. 파일은 `LIBRARY_DIR`, 메타데이터는 `library_addon` 테이블(`docs/sql/library-addon.sql`).
- 작성자가 재배포를 막아 둔 애드온을 올리는 것은 운영자 책임이다. 올릴 때 출처·허락 메모(`note`)를 남기고 작성자가 요청하면 바로 지운다.

## 설치 기록과 세팅 공유 (setup/)
- 사용자는 자기 설치 목록을 "세팅"(이름 붙인 애드온 묶음)으로 만들어 **지정한 사람**에게 공유하고, 받은 사람은 한 번에 설치한다. 전체 공개는 없다(공유 대상은 만든 사람이 직접 지정).
- 앱이 설치·업데이트·삭제한 뒤 서버에 알려(`/api/v1/installs/events`) 계정·환경별 현황(`account_addon`)과 이력(`addon_event`)을 쌓는다. 알리기에 실패해도 설치는 성공이며, 설치 상태 판단은 여전히 PC 폴더와 로컬 해시 기록으로 한다(서버 기록은 안내용).
- 세팅·현황은 자료실 폴더 이름으로 애드온을 가리킨다(`library_addon` FK 없음. 자료실에서 지워졌다 다시 올라와도 끊기지 않게). 세팅에는 자료실에 있는 애드온만 담고, 자료실에서 사라진 것은 `missingAddons` 로 따로 알린다.
- 고치기·지우기는 만든 사람만 한다. 남의 세팅은 존재를 알리지 않으려고 NOT_FOUND 로 답한다. 받은 사람에게는 다른 공유 대상 목록을 내려주지 않는다. 공유 대상은 로그인 ID 로 지정하고(순차 PK 비노출), 승인된(ACTIVE) 계정만 허용한다.
- 세팅으로 설치해도 항상 자료실의 검사된 파일만 받는다(해시 대조 포함). 이 경로로 서버가 모르는 파일이 PC 에 쓰이지 않게 한다.
- 스키마는 `docs/sql/setup.sql`. 예전 `installed_addon`(CurseForge ID 기준)은 이어 쓰지 않는다.
- **보관함(`addon_snapshot`)**: 내 애드온 목록의 기록. 앱이 폴더를 읽을 때 목록이 바뀌었으면 자동으로 남기고(`useAutoSnapshot`, 계정당 최근 10개), 이름을 붙여 직접 보관할 수도 있다(20개). 와우를 새로 깔거나 폴더를 다 지워도 기록으로 그대로 다시 설치하거나 편지로 보낼 수 있게 하려는 것이다. **빈 목록은 기록하지 않는다**(실수로 다 지운 직후의 "없음"이 좋은 기록을 밀어내지 않게). 기록은 계정 단위이고 환경은 요청값이 아니라 토큰 계정 소유 확인으로만 쓴다.
- **우편함**: 세팅 공유를 편지로 보여 준다(받은 편지·보낸 편지, 안 읽은 표시 `addon_setup_target.read_at`, 편지글 1000자). 읽음 표시는 내게 온 편지에만 가능하다. 기존 DB 는 `docs/sql/snapshot-mailbox.sql` 로 보강한다(새 DB 는 `init-all.sql` 에 포함).

## 데스크톱 앱 (desktop/)
- Chrome 은 `Program Files` 같은 시스템 폴더를 선택하지 못하게 막는다. 이를 피하려고 웹 화면을 띄우는 Electron 껍데기를 두고, **PC 폴더 작업만** 앱(main)이 맡는다. 서버는 여전히 로컬 폴더를 건드리지 않는다.
- 프론트는 `src/lib/nativeFs.ts` 어댑터로 앱의 `window.addonFs` 를 폴더 핸들처럼 다룬다. 폴더 작업(설치·동기화)은 앱에서만 되고, 일반 브라우저에서는 자료실 목록 보기와 앱 다운로드만 한다.
- 앱은 폴더를 두 종류로 연결한다: **AddOns 폴더**(사용자, 읽기·쓰기)와 **자료실 원본 폴더**(관리자, **읽기 전용** — 쓰기·폴더 생성·삭제를 앱이 거부한다).
- **앱은 화면(웹)을 믿지 않는다.** 요청 출처 검사, AddOns 연결은 `Interface\AddOns` 형태만, 경로는 이름 조각 배열로 받아 `..`·드라이브·링크 거부. 이 검사를 약화시키는 변경은 하지 않는다(`desktop/test` 로 검증).
- 빌드: `cd desktop && npm run dist`(또는 Gradle `desktopDist`) → `desktop/release/AddonManager-Setup.exe` + `latest.yml`. 자세한 내용은 `desktop/README.md`.
- 배포: 관리자가 웹 "앱 배포" 메뉴에서 위 두 파일을 올린다(`/api/v1/admin/app-release`, ADMIN 전용, 8MB 조각 업로드). 서버가 SHA-512·크기를 대조해 `DOWNLOADS_DIR` 에 게시하고 `/downloads/**` 로 서빙한다. 앱은 `latest.yml` 로 자동 업데이트한다. 버전은 빌드 때 `메이저.마이너.커밋수` 로 자동 결정되므로(`desktop/scripts/dist.js`) **커밋한 뒤 빌드**한다. 직접 올리는 것은 `package.json` 의 메이저.마이너뿐이다.
- 이 배포 통로는 모든 사용자 PC 에 코드를 보낼 수 있으므로 ADMIN 권한 보호(`AdminReleaseSecurityTest`)와 업로드 검증을 약화시키지 않는다.

## 코딩 규칙
- 생성자 주입(@RequiredArgsConstructor + private final)만 사용.
- 서비스: 클래스 @Transactional(rollbackFor = Exception.class), 조회 메서드는 readOnly = true.
- 외부 발송(웹소켓·외부 API)은 afterCommit에서 실행.
- 성공 응답은 DTO 그대로 ResponseEntity로, 실패는 BusinessException(ErrorCode) → GlobalExceptionHandler → ErrorResponse.
- MyBatis: #{}만 사용(${} 금지), @Param 명시, SQL 대문자·앞 콤마, snake_case AS camelCase.
- 모든 API에 @Operation(summary, description) 한글 작성. URL은 /api/v1/{영역}/{기능}.
- 주석은 "왜"를 한글로. 보안상 이유가 있는 코드는 이유를 반드시 남긴다.
- 메서드·JSON 필드는 camelCase.
- 역할 분담: 단건 저장/조회·상태 변경은 JPA, 목록·통계·조인은 MyBatis.

## 보안
- 비밀값은 application.yml에 쓰지 않는다. ${ENV} 로만.
- 사용자 ID는 @AuthenticationPrincipal Jwt 에서 꺼낸다. 요청 값의 사용자/소속 ID는 신뢰하지 않는다.
- 공개 경로 추가 시 SecurityConfig의 상수 배열에 이유 주석과 함께 추가한다. 기본은 인증 필요, 나머지는 denyAll.
- 비밀번호·토큰·개인정보는 로그에 남기지 않는다.
- 순차 PK를 외부 식별자로 노출하지 않는다(필요 시 UUID/공개키 사용).
- 애드온 이름·메모 등 사용자·관리자 입력은 프론트에서 텍스트로만 그린다(`dangerouslySetInnerHTML` 금지). 이 웹은 데스크톱 앱에서 PC 폴더를 다루는 통로가 있어 XSS 가 특히 위험하다.
- WebSocket은 STOMP CONNECT 시 JWT를 검증하고, 메시지 발신자는 클라이언트 값이 아니라 세션 값으로 덮어쓴다.

## DB
- ddl-auto: none. 스키마 변경은 docs/sql/*.sql 스크립트로 남긴다(Flyway 도입 시 전환).
- 공통 컬럼: created_at, updated_at, deleted_at(소프트 삭제) — DB DEFAULT로 관리.
- 채팅 메시지 등 비정형·대량 데이터는 MongoDB, 나머지는 MariaDB.

## 서비스 개요
- 와우포에버 애드온 자료실 웹앱. 관리자가 애드온을 모아 둔 폴더를 데스크톱 앱으로 읽어 서버 자료실에 올리고(동기화), 사용자는 데스크톱 앱에서 자료실의 애드온을 설치·업데이트한다. CurseForge 등 외부 서비스와 연동하지 않는다.
- 서버는 사용자 PC와 분리되어 있어 서버가 로컬 폴더를 읽고 쓰지 않는다.
- 계정: 가입(PENDING) → 관리자 승인(ACTIVE) 후 로그인. 사용자별로 여러 게임 환경(game_environment)을 관리한다.
- 프론트는 `frontend/`(React+Vite+TS)에 두고, Docker 멀티스테이지 빌드로 Spring static 에 넣어 한 컨테이너로 실행한다.

## 테스트
- 정책/규칙은 Mockito 단위 테스트로 검증(@ExtendWith(MockitoExtension.class), AssertJ).
- 테스트 클래스 상단 Javadoc에 검증하는 정책을 한글로 요약.

## 필수 환경변수
docker-compose 와 이름을 맞춘다.
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`,
(MongoDB 는 현재 비활성화 상태. 복구 시 `SPRING_DATA_MONGODB_URI` 필요)
`JWT_ACCESS_SECRET`(32바이트 이상)
(선택: `DOWNLOADS_DIR`(앱 설치 파일 폴더), `LIBRARY_DIR`(애드온 자료실 파일 폴더), `SERVER_PORT`(기본 5534), `JWT_ACCESS_VALID_SECONDS`, `ADMIN_LOGIN_ID`/`ADMIN_PASSWORD`(최초 관리자 생성), `CORS_ALLOWED_ORIGINS`(프론트 분리 시))
