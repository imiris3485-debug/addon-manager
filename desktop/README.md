# Addon Manager 데스크톱 앱

웹 화면(`https://addon-manager.shapinel.com`)을 창에 띄우고, **PC 의 AddOns 폴더 작업만** 앱이 맡는다.
브라우저(Chrome)는 `Program Files` 같은 시스템 폴더를 선택하지 못하게 막지만, 앱은 그 제한이 없다.

```
웹 화면(React) ──window.addonFs──▶ preload ──IPC──▶ main(Node fs) ──▶ AddOns 폴더
```

- 계정·CurseForge 연동은 지금처럼 서버가 맡는다. 앱은 서버와 직접 통신하지 않는다.
- 일반 브라우저로 접속하면 기존처럼 폴더 선택 방식으로 동작한다(웹 버전 유지).

## 개발

```powershell
cd desktop
npm install
npm test           # 보안 로직(경로 검증·정션 차단·폴더 탐색) 테스트
```

개발 중에 앱을 띄우려면 웹을 먼저 실행한다.

```powershell
# 터미널 1: 백엔드(5534) 와 프론트(5173)
cd frontend; npm run dev
# 터미널 2
cd desktop; npm start      # 개발 모드에서는 http://localhost:5173 을 연다
```

다른 주소를 열고 싶으면(개발 중에만) `ADDON_MANAGER_URL` 환경변수를 지정한다.
**배포본은 `https://addon-manager.shapinel.com` 으로 고정**되어 있어 환경변수로 바꿀 수 없다(`src/config.ts`).

## 설치 파일 만들기 (Windows)

```powershell
cd desktop
npm install
npm run dist
```

결과물: `desktop/release/AddonManager-Setup.exe` (약 116MB, NSIS 설치 파일)

- 설치 파일은 **사용자별 설치**(관리자 권한 불필요)이고, 한 번 실행하면 설치되어 바로 시작된다.
- 파일 이름에 버전을 넣지 않는다. 웹의 다운로드 링크(`/downloads/AddonManager-Setup.exe`)가 고정되어야 해서다.
  버전은 `desktop/package.json` 의 `version` 으로 정하고 배포할 때마다 올린다.
- Windows 에서 빌드해야 한다(설치 파일 도구가 Windows 용).

## 새 빌드 배포하기 (관리자 웹에서 업로드)

관리자 계정으로 로그인하면 **"앱 배포"** 메뉴가 보인다. 일반 계정에는 메뉴가 없고, 서버도 ADMIN 권한이 아니면 거부한다.

1. `desktop/package.json` 의 `version` 을 **올린다**(예: 0.1.0 → 0.2.0). 같은 버전이면 실행 중인 앱이 업데이트를 받지 않는다.
2. `npm run dist` (또는 Gradle `desktopDist`)로 빌드한다.
3. 웹의 **앱 배포** 화면에서 `desktop/release` 의 **`AddonManager-Setup.exe` 와 `latest.yml` 두 파일을 함께** 선택해 올린다.
4. 끝. 웹의 "앱 다운로드" 화면이 새 버전으로 바뀌고, 실행 중인 앱은 최대 4시간 안에(재시작하면 바로) 새 버전을 받는다.

서버가 하는 검증: 설치 파일의 SHA-512·크기가 `latest.yml` 과 같은지, Windows 실행 파일(MZ)인지,
게시된 버전보다 낮지 않은지를 확인한다. 하나라도 어긋나면 게시하지 않는다.
설치 파일은 프록시(Cloudflare)의 요청 크기 제한(100MB)을 피하려고 8MB 조각으로 나눠 올린다.

### 서버 준비 (한 번만)

서버가 설치 파일을 저장하고 내려줄 폴더가 필요하다. docker-compose 의 `app` 서비스에 추가한다.
```yaml
volumes:
  - ./downloads:/downloads          # 업로드한 파일이 컨테이너를 다시 만들어도 남도록 서버 폴더에 둔다(쓰기 가능)
environment:
  DOWNLOADS_DIR: /downloads
```
`mkdir -p ~/myapp/downloads` 로 폴더를 만들고 `docker compose up -d --no-deps --force-recreate app`.
`DOWNLOADS_DIR` 을 비워 두면 배포 기능과 다운로드 경로가 모두 꺼진다.
컨테이너 안의 앱 계정이 이 폴더에 쓸 수 있어야 한다(쓰기 오류가 나면 폴더 권한을 확인).

설치 파일과 `latest.yml` 은 URL 만 알면 로그인 없이 받을 수 있다(비밀 정보가 아니다).

## 자동 업데이트

- 앱은 시작할 때와 4시간마다 `https://addon-manager.shapinel.com/downloads/latest.yml` 을 확인한다.
- 새 버전이 있으면 백그라운드로 받고, "지금 재시작 / 나중에" 를 묻는다. 나중에를 골라도 앱을 닫을 때 적용된다.
- 낮은 버전으로 되돌리는 업데이트는 받지 않는다. 개발 모드(`npm start`)에서는 확인하지 않는다.
- ⚠️ **코드 서명이 없어서** 업데이트의 무결성은 HTTPS 와 `latest.yml` 의 SHA-512 에 의존한다.
  따라서 **관리자 계정이 뚫리면 모든 사용자 PC 에 코드를 배포할 수 있다.** 관리자 비밀번호를 강하게 두고,
  외부에 널리 배포하기 전에 코드 서명 인증서를 도입하는 것을 권장한다.

## 배포 전에 꼭 확인할 것

1. **웹을 먼저 배포한다.** 앱은 배포된 사이트를 그대로 띄운다. 파일 기능을 쓰는 새 웹 화면
   (`frontend/src/lib/nativeFs.ts`)이 서버에 올라가 있지 않으면 앱을 설치해도 예전 화면이 나온다.
2. **코드 서명이 없으면** 처음 실행할 때 Windows SmartScreen 이 "알 수 없는 게시자" 경고를 띄운다.
   `추가 정보 → 실행` 으로 진행할 수 있다. 외부 사용자에게 배포하려면 코드 서명 인증서를 구입해
   `win.certificateFile` 설정을 추가하고, `package.json` 의 `signAndEditExecutable: false` 는 지운다.
3. 앱 아이콘은 아직 기본(Electron) 아이콘이다. `build.win.icon` 에 `.ico` 를 지정하면 바뀐다.
4. 처음 설치한 사용자는 이후 새 버전을 자동으로 받는다(아래 "자동 업데이트"). 첫 설치 파일만 웹에서 받으면 된다.

## 보안 설계

이 앱은 PC 의 파일을 쓰고 지우므로 **웹 화면을 믿지 않고** 앱(main)이 매 요청마다 검사한다.

| 위협 | 대응 | 위치 |
|---|---|---|
| 다른 사이트·iframe 이 파일 기능을 호출 | 요청을 보낸 화면이 `addon-manager.shapinel.com` 의 메인 프레임인지 매번 확인 | `main.ts` `assertTrusted` |
| 앱 창이 다른 사이트로 이동 | `will-navigate`/`will-redirect` 에서 차단하고 기본 브라우저로 연다(https 만) | `main.ts` |
| 화면이 임의의 폴더를 연결 | 화면은 경로가 아닌 **불투명 키**만 받는다. 연결 가능한 폴더는 `…\Interface\AddOns` 형태뿐이고 선택은 OS 창·자동 검색으로만 이루어진다 | `rootStore.ts`, `locator.ts` |
| `../` 등으로 폴더 밖 접근(zip slip 포함) | 경로를 문자열이 아닌 이름 조각 배열로 받고 `..`, 구분자, 드라이브(`C:`), 대체 스트림(`a:b`), 장치 이름(`CON`), 끝의 점·공백을 거부 | `pathGuard.ts` |
| 정션·심볼릭 링크로 우회 | 경로 중간에 링크가 있으면 읽기·쓰기·삭제 거부, 목록에서도 제외 | `pathGuard.ts` `assertNoLinks` |
| AddOns 폴더 통째 삭제 | 기준 폴더 자체의 삭제 거부 | `fsOps.ts` |
| 거대 파일·잘못된 데이터 | 파일당 300MB 상한, `Uint8Array` 만 허용 | `fsOps.ts` |
| 앱 내부 접근 | `contextIsolation`, `sandbox`, `nodeIntegration: false`, 권한 요청 전부 거부, `webview` 금지 | `main.ts` |

zip 해제는 웹 화면(`installer.ts`)이 하지만, 그 결과를 쓰는 모든 경로는 위 검사를 다시 통과해야 한다.
검사 로직은 `npm test` 로 검증한다(`test/` 폴더, 정션 우회 시도 포함).
