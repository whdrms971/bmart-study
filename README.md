# BARO-PICK Delivery (라스트마일) — 구현·테스트 작업공간

고객 Flutter 앱 + 라이더 Flutter 앱 + Spring Boot Delivery 서비스. OMS·MFC 는 **계약 기반 Mock**(local-test 전용), 배차·인계 검증·위치 수집·완료 판정은 **실제 Delivery 구현**이다.

| 경로 | 내용 |
|---|---|
| `delivery-service/` | Spring Boot 4.1 / Java 21. Delivery 도메인 + `testsupport`(Mock OMS/MFC, `SIMULATED_ROUTE` 재생 엔진) |
| `simulator-admin/` | Vite + React + TypeScript 시뮬레이터·Mock 조작 화면 |
| `apps/customer_app` | 고객 Android 앱 `com.baropick.delivery.customer` |
| `apps/rider_app` | 라이더 Android 앱 `com.baropick.delivery.rider` |
| `apps/delivery_common` | 두 앱 공통(REST·STOMP·로그인·카카오 지도 위젯) |
| `scripts/` | PowerShell 실행·종료·E2E 스크립트 |
| `docs/delivery-implementation/` | BASELINE · PLAN · DECISIONS · CONTRACTS · TEST_MATRIX · SIMULATOR_RUNBOOK · FINAL_REPORT |
| `docs/business/` | 원본 비즈니스 문서(business.zip) |

## 1. 사전 요구사항

- JDK 21, Node 20+, Flutter 3.47 (Android SDK, `flutter doctor` 통과)
- Android: 실기기 2대(USB 디버깅) 또는 에뮬레이터 2대(§3.2)
- `JAVA_HOME` 이 실제 JDK 21 설치 폴더를 가리켜야 한다. 잘못된 경로면 `gradlew` 가 `JAVA_HOME is set to an invalid directory` 로 실패한다.
- 환경변수 **이름**(값은 저장소에 두지 않음): `.env.example` 참고
  - `DELIVERY_TEST_PASSCODE`(필수, 8자+), `KAKAO_REST_API_KEY`(서버 전용), `DELIVERY_AUTH_SECRET`(선택, 32자+)
  - 영구 설정은 Windows "시스템 환경 변수 편집" → 사용자 변수. PowerShell `$env:X = ...` 는 그 창에서만 유효하다.
  - `KAKAO_REST_API_KEY` 가 없으면 ETA 는 "확인 불가", 시뮬레이터는 **직선 FIXTURE 경로만** 사용 가능(도로를 따라가지 않음).

## 2. 서버·관리 화면

```powershell
$env:DELIVERY_TEST_PASSCODE = '<임의 값>'
.\scripts\start-backend.ps1 -Background      # http://0.0.0.0:8080, 휴대폰용 LAN 주소 후보 출력
.\scripts\start-admin-ui.ps1                 # http://127.0.0.1:5173  (ADMIN-TEST / 패스코드)
.\scripts\e2e-mode-a.ps1 -Rate 10            # 자동 모의 E2E (FIXTURE). -RouteSource KAKAO_DIRECTIONS 로 카카오 실경로
.\scripts\stop-backend.ps1                   # 종료 (Delivery java 프로세스만 종료, 같은 포트의 다른 프로그램은 건드리지 않음)
```

**기본 포트(8080 / 5173)를 다른 프로그램이 쓰는 PC** — 스크립트는 사용 중인 포트로 기동하지 않고 오류를 낸다. 포트를 바꿔 실행:

```powershell
.\scripts\start-backend.ps1 -Background -Port 18080
.\scripts\start-admin-ui.ps1 -Port 15173 -Backend http://127.0.0.1:18080
.\scripts\e2e-mode-a.ps1 -Base http://127.0.0.1:18080 -Rate 10
.\scripts\stop-backend.ps1 -Port 18080
```

(사용 중 포트 확인: `Get-NetTCPConnection -LocalPort 8080 -State Listen`)

**PostgreSQL 로 실행** (기본은 H2 파일 DB). 저장소에 compose 파일은 없으므로 컨테이너를 직접 띄운다:

```powershell
docker run -d --name delivery-pg -e POSTGRES_DB=delivery -e POSTGRES_USER=delivery -e POSTGRES_PASSWORD=<임의 값> -p 127.0.0.1:25432:5432 postgres:17-alpine
$env:DELIVERY_DB_URL = 'jdbc:postgresql://127.0.0.1:25432/delivery'; $env:DELIVERY_DB_USER = 'delivery'; $env:DELIVERY_DB_PASSWORD = '<같은 값>'
.\scripts\start-backend.ps1 -Background -Postgres      # Flyway 가 스키마 생성
```

테스트 계정: 고객 `C-TEST-01`, `C-TEST-02` / 라이더 `R-TEST-01~03` / 관리자 `ADMIN-TEST` (모두 같은 패스코드).
자동 테스트: `cd delivery-service; .\gradlew.bat test` (H2. 카카오 실호출 테스트는 `.\gradlew.bat test -PkakaoLive`).
PostgreSQL 에서 전체 테스트: `.\scripts\test-postgres.ps1` (일회용 컨테이너를 띄우고 끝나면 삭제, Docker 필요).
부하 테스트: `.\scripts\load-test.ps1 -Riders 30 -DurationSeconds 60` (일회용 PostgreSQL + 포트 18082 의 별도 서버·부하 전용 계정, 끝나면 정리. Docker·Node 필요. 실행 중인 개발 서버·DB 는 건드리지 않음).
CI: `.github/workflows/delivery-ci.yml` — push/PR 시 백엔드(H2·PostgreSQL), Flutter 3개 패키지 analyze·test, 관리 화면 빌드. 비밀값을 쓰지 않으며 카카오 실호출 테스트는 제외.
관리 화면 UI 스모크(설치된 Chrome 사용): `cd simulator-admin; $env:UI_URL='http://127.0.0.1:5173'; $env:API_URL='http://127.0.0.1:8080'; npm run ui-smoke`

## 3. Android 앱 (A=고객, B=라이더)

### 3.1 실기기

1. 카카오 개발자 콘솔 → 플랫폼 Android: 패키지 `com.baropick.delivery.customer`, `com.baropick.delivery.rider`, **빌드하는 PC 의 디버그 키 해시**를 등록한다. 디버그 키는 PC 마다 다르므로 PC 를 바꾸면 해시를 추가 등록해야 지도가 뜬다.
   - 등록된 해시: 노트북 `hvQoX18nETfKZk8qMI2XBBduHAA=`, 데스크톱 `VmuoiRD0Yr/n5tls2pnCNDFfakg=`
   - 현재 PC 해시 확인:
     ```bash
     keytool -exportcert -alias androiddebugkey -keystore "$USERPROFILE/.android/debug.keystore" -storepass android | openssl sha1 -binary | openssl base64
     ```
2. 각 앱 폴더에 `kakao.local.json` 생성(커밋 금지, `.gitignore` 처리됨) — `kakao.local.json.example` 참고:
   ```json
   { "API_BASE_URL": "http://<PC_LAN_IP>:8080", "KAKAO_NATIVE_APP_KEY": "<네이티브 앱 키>" }
   ```
3. 휴대폰과 PC 를 같은 Wi-Fi 에 두고(게스트망 격리·방화벽 주의), PC 방화벽에서 TCP 8080 인바운드 허용이 필요할 수 있다(사용자 승인 필요).
4. 기기 한 대씩 USB 연결 후 설치:
   ```powershell
   adb devices -l
   cd apps\customer_app; flutter run --debug --dart-define-from-file=kakao.local.json   # 기기 A
   cd apps\rider_app;    flutter run --debug --dart-define-from-file=kakao.local.json   # 기기 B
   ```
   (또는 `flutter build apk --debug --dart-define-from-file=kakao.local.json` 후 `adb install -r build\app\outputs\flutter-apk\app-debug.apk`)
5. 로그인 화면의 서버 주소는 `localhost` 가 아니라 PC LAN IP. HTTP 평문은 **debug 빌드에만** 허용된다.

### 3.2 에뮬레이터 (실기기 없이 두 앱 조작)

- 서버 주소는 `http://10.0.2.2:<서버 포트>` (에뮬레이터에서 본 PC). `kakao.local.json` 의 `API_BASE_URL` 에 넣는다.
- **카카오 지도 SDK 는 ARM 네이티브 라이브러리(`libK3fAndroid.so`)만 제공**한다. x86_64 로 설치되면 앱 시작 시 `UnsatisfiedLinkError` 로 종료된다. Google Play 시스템 이미지(API 30+)는 ARM 변환을 지원하므로 **arm64 로 빌드하고 ABI 를 강제해 설치**한다:
  ```powershell
  cd apps\customer_app
  flutter build apk --debug --target-platform android-arm64 --dart-define-from-file=kakao.local.json
  adb -s emulator-5554 install -r --abi arm64-v8a build\app\outputs\flutter-apk\app-debug.apk
  ```
  (ARM 변환 지원 확인: `adb shell getprop ro.product.cpu.abilist` 에 `arm64-v8a` 포함. ARM 변환이라 첫 실행이 느리다.)
- 같은 AVD 로 2대를 띄우려면 **두 인스턴스 모두** `-read-only`:
  ```powershell
  & "$env:ANDROID_HOME\emulator\emulator.exe" -avd <AVD 이름> -port 5554 -read-only -no-snapshot-save
  & "$env:ANDROID_HOME\emulator\emulator.exe" -avd <AVD 이름> -port 5556 -read-only -no-snapshot-save
  ```
- 에뮬레이터 카메라는 가상 장면이라 포장 QR 을 비출 수 없다. 라이더 앱 스캔 화면 하단의 **코드 직접 입력**(`BPKG|…`)에 관리 화면 QR 아래 코드를 넣는다. 전달 사진은 에뮬레이터 카메라 앱으로 촬영 가능.
- 키 없이(`KAKAO_NATIVE_APP_KEY` 빈 값) 빌드하면 지도 대신 좌표만 표시되며 x86_64 설치도 동작한다.

## 4. 테스트 순서

- **모드 A (원거리 SIMULATED_ROUTE):** `docs/delivery-implementation/SIMULATOR_RUNBOOK.md`. 경로 출처는 **`KAKAO_DIRECTIONS`(실제 도로) 권장**. `FIXTURE` 는 손으로 작성한 좌표 18개를 직선으로 잇는 경로라 건물·공원을 가로지른다(연결 검증용).
- **모드 B (근거리 실 GPS):** 관리 화면 Mock 탭에서 `MFC-TEST-01` 을 실제 테스트 장소 좌표로 저장 → 고객 앱에서 `MFC-TEST-01` + 근처 안전한 주소로 주문 → 라이더 앱 운행 시작(위치 권한 허용) → 수락·MFC 도착 → 관리 화면에서 포장 완료·QR 표시 → 스캔 → 배송 출발 → **안전한 짧은 구간 이동**(이동 중 휴대폰 조작 금지) → 고객 앱 마커 갱신 확인 → 도착·사진·완료.
- 결과 기록: `docs/delivery-implementation/TEST_MATRIX.md`

## 5. 보안 메모

- 카카오 REST 키는 서버 환경변수로만 사용하며 로그·응답·리포트에 출력하지 않는다. 네이티브 앱 키는 APK 에 포함되는 키이므로 콘솔에서 패키지·키 해시로 제한한다.
- 테스트 로그인·Mock·시뮬레이터·`/test-api/**` 는 `local-test` 프로필에만 존재한다(`prod` 동시 활성화 시 기동 실패).
- 원격 push·운영 배포는 하지 않는다.
