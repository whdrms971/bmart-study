# SIMULATOR_RUNBOOK — 모드 A `SIMULATED_ROUTE` 실행·조작·종료 (Windows PowerShell)

> 시뮬레이터는 **local-test 프로필 서버 안의 테스트 전용 재생 엔진**이다. 운영(prod) 프로필에는 빈·API·테이블이 없다.
> 위치는 라이더 휴대폰 GPS 가 아니라 서버 내부 주입 어댑터가 넣으며, 실 GPS 와 **같은** 위치 검증·저장·고객 전파 경로를 통과한다.
> 재생 종료(FINISHED) ≠ 배송 완료. 완료는 라이더 앱(또는 API)의 사진 증빙 완료로만 가능하다.

## 1. 사전 준비 (최초 1회)

```powershell
java -version          # 21 이상
node -v                # 20 이상 (관리 화면)
# 사용자 환경변수 (키 값은 문서·코드에 쓰지 않는다)
$env:DELIVERY_TEST_PASSCODE = '<8자 이상 임의 값>'        # 테스트 계정 공용 패스코드
$env:KAKAO_REST_API_KEY     = '<이미 설정되어 있으면 생략>' # 없으면 FIXTURE 경로만 사용 가능
$env:DELIVERY_AUTH_SECRET   = '<32자 이상 임의 값>'        # 선택. 없으면 재기동마다 재로그인
```

## 2. 기동 / 종료

| 작업 | 명령 | 결과 |
|---|---|---|
| 서버 기동(백그라운드) | `.\scripts\start-backend.ps1 -Background` | `서버 준비 완료 (health UP)`, 휴대폰용 `API_BASE_URL 후보` 출력 |
| 서버 기동(포그라운드) | `.\scripts\start-backend.ps1` | Ctrl+C 로 종료 |
| 관리 화면 | `.\scripts\start-admin-ui.ps1` → 브라우저 **http://127.0.0.1:5173** | `ADMIN-TEST` + 패스코드 로그인 |
| 자동 모의 E2E | `.\scripts\e2e-mode-a.ps1 -Rate 10` (`-RouteSource KAKAO_DIRECTIONS` 선택) | `reports\runs\mode-a-*.md` |
| 서버 종료 | `.\scripts\stop-backend.ps1` | 서버와 함께 모든 재생 작업·위치 전송 종료 |
| 데이터 초기화 | `.\scripts\stop-backend.ps1; .\scripts\reset-local-data.ps1` | `delivery-service\data` 삭제 |

로그: 서버 `reports\runs\backend.log`, 시나리오 증거 `reports\runs\simulations\<scenarioId>.jsonl`(실시각·가상시각·전송 seq·판정).

## 3. 관리 화면으로 한 번 돌려보기 (약 3분)

1. **Mock OMS/MFC 탭** → `배송지 = 테스트 고객 배송지 T1` → MFC `MFC-TEST-REMOTE-01`(SIMULATED_ROUTE) → `테스트 주문 생성`.
   (고객 앱의 `테스트 주문`도 같은 API 를 쓴다.)
2. 라이더 앱(또는 API)에서 **운행 시작 → 제안 수락 → MFC 도착**.
3. Mock 탭에서 해당 배송 `포장 완료` → `QR 보기` → 라이더 앱으로 화면의 QR 을 **포장 개수만큼 전부** 스캔 → `배송 출발`.
4. **SIMULATED_ROUTE 제어 탭**
   - 테스트 배송 선택(라이더 자동 입력) → 경로 출처 `FIXTURE` / FIXTURE `BUSAN-CENTUM-HAEUNDAE-T1` → 배속 → (선택) 계획 장애 추가
   - `생성` → `검증·미리보기`(경로 SVG 표시) → `시작`
   - 진행 중 `일시정지`/`재개`, 배속 `1×/5×/10×`, `지금 장애 주입`
   - 화면 지표: 진행 거리·가상 경과·마지막 전송(실시각)·전송/최신반영·역순/중복/거부/지연·**고객 API 기준 위치**·ETA·오류. `SIMULATED`, `FIXTURE/KAKAO_DIRECTIONS` 배지 표시
5. 재생이 `FINISHED` 가 되어도 배송은 `IN_TRANSIT` 그대로다. 라이더 앱에서 `고객 도착 → 사진 촬영 → 전달 완료`.
   - 고객 도착은 **배송지 150m 이내 · 위치 측정 후 30초 이내 · 정확도 50m 이하**에서만 허용된다(DECISIONS.md A-01~A-04). 경로 중간에서는 거부된다.
   - `FINISHED` 이후 재생기는 **배송지 정차 위치**를 계속 보낸다(화면 "배송지 정차", 최대 30분, 완료·취소·재배정 시 자동 중단). 그래서 재생이 끝나고 시간이 지나도 도착 판정이 가능하다.
   - 거리·위치 문제로 도착할 수 없으면 라이더 앱 "도착 예외 요청" → 관리 화면 **도착 예외** 탭에서 처리 사유 입력 후 승인/반려. 시뮬레이터 전용 우회 기능은 없다.
6. `종료` → `초기화`(READY 로 되돌아가 같은 경로 재실행 가능) 또는 `삭제`.

버튼은 상태에 맞지 않으면 비활성화되며, 서버도 잘못된 순서를 `409 INVALID_SIMULATION_STATE` 등으로 거부한다.

## 4. 제어 API (ADMIN 토큰, local-test 전용)

| 동작 | 요청 | 허용 상태 → 결과 |
|---|---|---|
| FIXTURE 목록 | `GET /test-api/simulations/fixtures` | |
| 생성 | `POST /test-api/simulations` `{deliveryId, riderId, routeSource: FIXTURE|KAKAO_DIRECTIONS, fixtureId?, rate: 1|5|10, speedMps?, seed?, faults?: [{type, atFraction, durationTicks?, durationSeconds?}]}` | → `CREATED`. 같은 배송 활성 시나리오 있으면 `409 DUPLICATE_RUN`, 실GPS 배송이면 `409 NOT_SIMULATED_DELIVERY` |
| 검증·미리보기 | `POST …/{id}/prepare`, `GET …/{id}/route` | CREATED/READY → `READY`. 경로 끝점 불일치 `409 ROUTE_ENDPOINT_MISMATCH`, 카카오 실패 `503 KAKAO_*` → `FAILED`(직선 대체 없음) |
| 시작 | `POST …/{id}/start` | READY → `RUNNING`. 인계 전 `409 HANDOVER_NOT_COMPLETED`, 라이더 불일치 `409 RIDER_MISMATCH`, 중복 `409 ALREADY_RUNNING` |
| 일시정지/재개 | `POST …/{id}/pause` · `…/resume` | RUNNING ↔ PAUSED |
| 배속 | `PUT …/{id}/rate {rate}` | 1/5/10 외 `400 INVALID_RATE` |
| 장애 즉시 주입 | `POST …/{id}/faults {type, durationTicks?, durationSeconds?}` | RUNNING/PAUSED |
| 종료 | `POST …/{id}/stop` | → `STOPPED` (작업 취소, 이후 전송 0). `FINISHED` 정차 전송 중이면 정차 전송만 중단 |
| 초기화 | `POST …/{id}/reset` | FINISHED/STOPPED/FAILED/READY → `READY` (실행 중이면 `409 STOP_FIRST`) |
| 상태 / 목록 / 삭제 | `GET …/{id}` · `GET /test-api/simulations` · `DELETE …/{id}` | |

장애 유형: `STOP`(정차 N초) · `DROP`(N틱 유실) · `DELAY`(N틱 보류 후 일괄) · `DUPLICATE` · `REORDER` · `ANOMALY`(범위 밖 + 순간이동) · `DISCONNECT`(N틱 끊김).

## 5. 동작 규칙

- 1틱 = 실시간 1초, 가상 시간 = 1초 × 배속. 틱마다 현재 위치 1개 생성(장애에 따라 전송 변화). 같은 seed·경로·장애·틱 시퀀스 → 같은 결과.
- 좌표 `measuredAt` = 시나리오 가상 시각, 서버 `receivedAt` = 실시각(둘 다 저장). 고객 화면 "마지막 갱신"은 실시각 기준.
- 출발점 = 배송 생성 시 고정된 MFC 좌표, 도착점 = 주문 시점 배송지 스냅샷. 라이더 휴대폰 좌표로 덮어쓰지 않는다.
- 기본 속도: FIXTURE 6 m/s, 카카오 = 거리/카카오 예상시간(2~20 m/s 범위).
- 배송 취소·완료·재배정이 커밋되면 즉시 주입 중단(FAILED/STOPPED). 매 틱 배정 버전도 재검사한다.
- ETA 는 카카오 Directions 실시각 기준(시뮬레이션 배속 미반영)이며 배송당 60초에 1회 이하 호출(P-08).

## 6. 오류 대응

| 증상 | 원인 / 조치 |
|---|---|
| 로그인 `TEST_LOGIN_DISABLED` | 서버 기동 전 `DELIVERY_TEST_PASSCODE` 미설정 → 설정 후 재기동 |
| 관리 화면 "테스트 지원 API 접근 불가" | local-test 가 아닌 서버이거나 서버 미기동 |
| 배차 제안이 오지 않음 | 라이더가 `운행·신규배차` 상태인지, 이미 3건 수행 중인지, 근거리 MFC 는 대기 위치가 반경 3km 내·5분 이내인지 |
| `ROUTE_ENDPOINT_MISMATCH` | FIXTURE 는 전용 테스트 배송지 T1 에서만 사용. 다른 주소는 `KAKAO_DIRECTIONS` |
| `KAKAO_KEY_MISSING / KAKAO_QUOTA_EXCEEDED` | 서버 키 미설정 / 일 무료량 초과 — FIXTURE 사용 |
| 고객 도착 `409 ARRIVAL_TOO_FAR` | 배송지 150m 밖. 재생이 끝날 때까지 기다리거나 도착 예외 요청 |
| 고객 도착 `422 LOCATION_REMEASURE_REQUIRED` | 최신 위치가 30초 초과(정차 전송이 끝났거나 시나리오 종료) 또는 정확도 불량. 시나리오 `초기화` 후 재실행하거나 예외 요청 |
| 고객 화면 위치가 안 바뀜 | 배송이 `PICKED_UP` 이전(공개 정책 P-05), 시나리오 FAILED(사유 확인), 고객 앱 연결 상태 칩 확인 |
