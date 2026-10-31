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

- JDK 21, Node 20+, Flutter 3.47 (Android SDK, `flutter doctor` 통과), Android 실기기 2대(USB 디버깅)
- 환경변수 **이름**(값은 저장소에 두지 않음): `.env.example` 참고
  - `DELIVERY_TEST_PASSCODE`(필수, 8자+), `KAKAO_REST_API_KEY`(서버 전용), `DELIVERY_AUTH_SECRET`(선택, 32자+)

## 2. 서버·관리 화면

```powershell
$env:DELIVERY_TEST_PASSCODE = '<임의 값>'
.\scripts\start-backend.ps1 -Background      # http://0.0.0.0:8080, 휴대폰용 LAN 주소 후보 출력
.\scripts\start-admin-ui.ps1                 # http://127.0.0.1:5173  (ADMIN-TEST / 패스코드)
.\scripts\e2e-mode-a.ps1 -Rate 10            # 자동 모의 E2E (FIXTURE). -RouteSource KAKAO_DIRECTIONS 로 카카오 실경로
.\scripts\stop-backend.ps1                   # 종료
```

테스트 계정: 고객 `C-TEST-01`, `C-TEST-02` / 라이더 `R-TEST-01~03` / 관리자 `ADMIN-TEST` (모두 같은 패스코드).
자동 테스트: `cd delivery-service; .\gradlew.bat test` (카카오 실호출 테스트는 `.\gradlew.bat test -PkakaoLive`).

## 3. Android 앱 (A=고객, B=라이더)

1. 카카오 개발자 콘솔 → 플랫폼 Android: 패키지 `com.baropick.delivery.customer`, `com.baropick.delivery.rider`, 키 해시(이 PC 디버그 키) `hvQoX18nETfKZk8qMI2XBBduHAA=`
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

## 4. 테스트 순서

- **모드 A (원거리 SIMULATED_ROUTE):** `docs/delivery-implementation/SIMULATOR_RUNBOOK.md`
- **모드 B (근거리 실 GPS):** 관리 화면 Mock 탭에서 `MFC-TEST-01` 을 실제 테스트 장소 좌표로 저장 → 고객 앱에서 `MFC-TEST-01` + 근처 안전한 주소로 주문 → 라이더 앱 운행 시작(위치 권한 허용) → 수락·MFC 도착 → 관리 화면에서 포장 완료·QR 표시 → 스캔 → 배송 출발 → **안전한 짧은 구간 이동**(이동 중 휴대폰 조작 금지) → 고객 앱 마커 갱신 확인 → 도착·사진·완료.
- 결과 기록: `docs/delivery-implementation/TEST_MATRIX.md`

## 5. 보안 메모

- 카카오 REST 키는 서버 환경변수로만 사용하며 로그·응답·리포트에 출력하지 않는다. 네이티브 앱 키는 APK 에 포함되는 키이므로 콘솔에서 패키지·키 해시로 제한한다.
- 테스트 로그인·Mock·시뮬레이터·`/test-api/**` 는 `local-test` 프로필에만 존재한다(`prod` 동시 활성화 시 기동 실패).
- 원격 push·운영 배포는 하지 않는다.
