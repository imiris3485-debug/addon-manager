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
- 핵심 로직은 `src/lib/`: `scanner`(폴더의 .toc 읽기) · `hash`(내용 해시) · `installer`(zip 검사·해제·폴더 쓰기) · `library`(설치 상태·의존성 순서·설치) · `librarySync`(관리자 원본 폴더 동기화) · `handles`/`nativeFs`(폴더 핸들).
- **내용 해시 규칙(`hash.ts`)은 서버 `AddonZipInspector` 와 같아야 한다.** 한쪽만 바꾸면 "내용이 같은데 다르다"고 판단한다. 바꾸면 양쪽을 함께 바꾸고 실제 폴더로 같은 값이 나오는지 확인한다.
- 운영 배포는 반드시 HTTPS 로 서빙한다.
- 토큰으로 읽은 역할(관리자 여부)은 화면 표시용일 뿐이며, 권한 검사는 서버 `@PreAuthorize` 가 한다.

## 애드온 자료실 (library/)
- **CurseForge 등 외부 서비스와 연동하지 않는다.** 관리자가 애드온을 모아 둔 폴더(원본 폴더)를 데스크톱 앱에서 읽어 서버에 올리고(동기화), 사용자는 자료실 목록에서 골라 설치·업데이트한다.
- 항목 하나 = 애드온 폴더 하나(zip 의 최상위 폴더가 이름). 내용 해시(`contentHash`)가 같으면 다시 올리지 않고, 사용자는 설치 때 기록한 해시와 비교해 업데이트를 판단한다.
- 같이 설치할 애드온 = `.toc` 의 `Dependencies`/`RequiredDeps` + 관리자가 지정한 `extra_requires`(다시 올려도 유지). 실제 애드온은 .toc 에 의존성을 선언하지 않는 경우가 많다(예: Questie → QuestieDB).
- **공개 경로(`/downloads` 등)로 내주지 않는다.** 받기는 인증이 필요한 `/api/v1/library/{id}/download` 로만 한다. 올리기·지우기·의존성 지정은 ADMIN 전용(`AdminLibrarySecurityTest` 가 고정).
- 올라온 zip 은 받은 사람들의 게임 폴더에 풀리므로 `AddonZipInspector` 가 올릴 때 검사한다: 경로 조작(zip slip)·Windows 특수 이름·실행 파일/스크립트(허용 확장자 목록만 통과)·압축 폭탄·중복 경로·최상위 파일·폴더 둘 이상·.toc 없음. 이 검사를 약화시키는 변경은 하지 않는다. 허용 확장자를 늘릴 때는 실행될 수 있는 형식을 넣지 않는다.
- 받는 쪽(`installer.ts`)도 같은 확장자·경로 검사를 다시 하고, 쓰기 전에 **받은 zip 의 해시가 목록의 해시와 같은지** 확인한다. 허용 확장자 목록은 서버와 프론트가 같아야 한다.
- 저장 파일 이름은 서버가 만든 UUID 만 쓴다(`<id>.zip`). 클라이언트가 준 이름·경로는 파일 이름에 쓰지 않는다. 파일은 `LIBRARY_DIR`, 메타데이터는 `library_addon` 테이블(`docs/sql/library-addon.sql`).
- 작성자가 재배포를 막아 둔 애드온을 올리는 것은 운영자 책임이다. 올릴 때 출처·허락 메모(`note`)를 남기고 작성자가 요청하면 바로 지운다.

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
(선택: `DOWNLOADS_DIR`(앱 설치 파일 폴더), `LIBRARY_DIR`(애드온 자료실 zip 폴더), `SERVER_PORT`(기본 5534), `JWT_ACCESS_VALID_SECONDS`, `ADMIN_LOGIN_ID`/`ADMIN_PASSWORD`(최초 관리자 생성), `CORS_ALLOWED_ORIGINS`(프론트 분리 시))
