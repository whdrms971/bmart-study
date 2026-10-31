# CONTRACTS — Delivery API·이벤트 계약 (v1, 2026-10-31)

> 기존 OpenAPI/AsyncAPI 가 없어 이 문서가 1차 계약이다 `[설계 제안]`. 실제 OMS/MFC 와 합의 전까지 Mock 이 이 계약을 따른다.
> 근거 코드: `delivery-service/src/main/java/com/baropick/delivery/**` `[코드 확인]`

## 0. 공통

| 항목 | 규칙 |
|---|---|
| 오류 응답 | `{"code": "...", "message": "..."}` + HTTP 상태. 검증 실패는 `VALIDATION_FAILED` + `fields[]` |
| 시각 | ISO-8601 UTC (`2026-10-31T10:00:00Z`) |
| 좌표 | `latitude`[-90,90], `longitude`[-180,180], 소수 도(WGS84). 카카오 Directions 응답 vertex 는 (경도, 위도) 순서 → 서버에서 변환 |
| 인증 | 앱/관리자: `Authorization: Bearer <JWT HS256>` (sub=사용자ID, role=CUSTOMER/RIDER/ADMIN). 내부: `X-Internal-Token` |
| 명령 멱등 | `(sourceSystem, requestId)`: 같은 본문 → 기존 결과, 다른 본문 → `409 IDEMPOTENCY_CONFLICT` |
| 이벤트 멱등 | `(sourceSystem, eventId)` inbox 로 1회 적용 |

### 식별자 (서로 혼동 금지)

| ID | 소유 | 예 | 비고 |
|---|---|---|---|
| `orderId` | OMS | `ORD-…` | 주문 1 : 배송 N |
| `deliveryId` | OMS 가 요청 시 발급, Delivery 가 원장 소유 | `DLV-…` | 배송 1 : 포장 N |
| `packageId` | MFC | `PKG-…` | QR = `BPKG|{packageId}|{token}` (token 검증은 MFC) |
| `handoverId` | MFC | `HOV-…` | 포장 인계 확정 사실 |
| `mfcId` | 거점 기준정보 | `MFC-TEST-REMOTE-01` | 가상 테스트 거점은 운영 좌표 아님 |
| `riderId` | Delivery | `R-TEST-01` | |
| `offerId` / attempt | Delivery | `OFR-…` / `ATT-…` | 배차 제안 / 배차 시도 |
| `scenarioId` | 시뮬레이터(테스트) | `SIM-…` | |
| `eventId` | 발행 시스템 | `EVT-…` | |

## 1. 상태

**배송(Delivery 정본):** `REQUESTED → OFFERING → ASSIGNED → ARRIVED_AT_MFC → PICKED_UP → IN_TRANSIT → ARRIVED_AT_DESTINATION → DELIVERED`, 그리고 `CANCELLED`.
- `PICKED_UP` 조건: MFC 가 포장 준비(PackagesReady)를 통지한 **모든** 포장이 MFC 인계 확정 + 배정 라이더 스캔.
- 픽업 전 배정 해제(라이더 배차취소/관리자 재배정) → `REQUESTED`, `assignmentVersion` 증가(이전 라이더 권한 회수).
- `DELIVERED` 는 라이더 완료 요청 + 사진 증빙 검증으로만. 시간 경과·GPS·시뮬레이터 종료로 전이하지 않음.

**포장(Delivery 관점):** `READY → HANDED_OVER` / `HANDOVER_UNKNOWN`(MFC 응답 불명) / `VOIDED`(취소).
**MFC Mock 포장:** `OPEN → SEALED → HANDED_OVER` / `VOIDED`.
**제안:** `OFFERED → ACCEPTED | REJECTED | EXPIRED | SUPERSEDED | CANCELLED`.

## 2. OMS → Delivery

### `POST /internal/delivery-requests` (X-Internal-Token)
```json
{ "sourceSystem": "OMS-MOCK", "requestId": "ORD-…-D1", "orderId": "ORD-…", "deliveryId": "DLV-…",
  "customerId": "C-TEST-01", "mfcId": "MFC-TEST-REMOTE-01",
  "destination": { "latitude": 35.1621, "longitude": 129.1602, "address": "…", "addressDetail": "101호" },
  "expectedPackageCount": 2, "totalWeightGrams": 3000, "customerNote": "…" }
```
- `201 {deliveryId, orderId, created:true, status, locationMode}` / 재전송 `200 created:false`
- `409 IDEMPOTENCY_CONFLICT`, `409 DELIVERY_ID_CONFLICT`, `400 UNKNOWN_MFC`, `400 INVALID_COORDINATE`
- 위치 모드는 **MFC 설정으로 서버가 고정**(요청 본문에 없음).

### `POST /internal/deliveries/{deliveryId}/cancel` (X-Internal-Token)
`{ "sourceSystem", "requestId", "reason" }` → `{deliveryId, result, status, message}`
- `result`: `CANCELLED` | `ALREADY_CANCELLED` | `REJECTED_HANDED_OVER`(회수 경로) | `NOT_CANCELLABLE_DELIVERED`
- 인계 결과 불명 포장이 있으면 `409 HANDOVER_CONFIRMING` (원결과 확인 후 재요청)
- 순서 원칙: OMS 는 먼저 MFC 출고 중지(StopWork)를 받고, 전 포장 STOPPED 인 배송만 취소 요청 (cancel-vs-handover S3-b)

## 3. MFC ↔ Delivery

### MFC → Delivery `POST /internal/mfc-events/packages-ready`
`{ "sourceSystem": "MFC-MOCK", "eventId": "PKR-…", "deliveryId", "packageIds": ["PKG-…"] }` → `{deliveryId, result: READY|ALREADY_READY|DUPLICATE_EVENT_IGNORED|DELIVERY_CANCELLED, packageCount}`
- 재포장(다른 구성) `409 REPACK_NOT_SUPPORTED` `[결정 필요]`

### Delivery → MFC (포트 `MfcHandoverPort`, Mock: `POST /mock-mfc/internal/handovers`)
요청 `{executionId, deliveryId, orderId, mfcId, packageId, qrCode, riderId, assignmentVersion}`
- `executionId = {deliveryId}:{packageId}:v{assignmentVersion}` — 재전송 시 MFC 가 기존 결과 반환(중복 인계·중복 재고 차감 없음)
- 응답 `{outcome: HANDED_OVER|REJECTED|NOT_FOUND, packageId, handoverId, handedOverAt, code, message}`
- 거절 코드: `QR_TOKEN_MISMATCH`, `PACKAGE_NOT_SEALED`, `INVALID_STATE`(중지된 포장), `ALREADY_HANDED_OVER`, `PACKAGE_NOT_IN_DELIVERY`
- 타임아웃/연결 실패 → Delivery 는 `HANDOVER_UNKNOWN` 기록, 재스캔 시 `GET /mock-mfc/internal/handovers/{executionId}` 로 원결과 조회 후 판단
- 재고 차감은 MFC 가 인계 확정 시점에만(Delivery 는 재고를 쓰지 않음)

## 4. 라이더 앱 → Delivery (`ROLE_RIDER`, 라이더 ID 는 토큰 subject)

| API | 설명 / 주요 오류 |
|---|---|
| `GET /api/rider/me` | 운행 상태 + 수행 중 배송 |
| `POST /api/rider/me/duty` `{dutyStatus: OFF_DUTY|ON_DUTY_ACCEPTING|ON_DUTY_PAUSED}` | 신규 제안만 영향. 수행 중 배송 자동 취소 없음 |
| `POST /api/rider/me/location` | 대기 위치(배차 반경 판정용) |
| `GET /api/rider/offers` | 활성 제안 |
| `POST /api/rider/offers/{offerId}/view` | 상세 확인 기록(미응답 만료 사유 구분) |
| `POST /api/rider/offers/{offerId}/accept` | `ASSIGNED` / `409 ALREADY_ASSIGNED, OFFER_EXPIRED, OFFER_NOT_ACTIVE, DELIVERY_CANCELLED, CAPACITY_EXCEEDED` / `403 NOT_YOUR_OFFER` |
| `POST /api/rider/offers/{offerId}/reject` `{reason}` | |
| `GET /api/rider/deliveries/{id}` | 배정 라이더만 (`403 NOT_ASSIGNED_RIDER`) |
| `POST …/arrive-mfc` | `ASSIGNED → ARRIVED_AT_MFC` |
| `POST …/handover-scans` `{qrCode}` | `{result: HANDED_OVER|ALREADY_HANDED_OVER|HANDOVER_RESULT_UNKNOWN, handedOver, total, deliveryStatus}` / `409 INVALID_STATE, PACKAGES_NOT_READY, PACKAGE_NOT_IN_DELIVERY, PACKAGE_VOIDED, MFC 거절코드` / `400 QR_UNREADABLE` |
| `POST …/start` | `PICKED_UP → IN_TRANSIT` / `409 HANDOVER_NOT_COMPLETED` |
| `POST …/locations` `{points:[{seq, latitude, longitude, accuracyMeters, speedMps, heading, measuredAt}]}` (≤100) | 출처 **DEVICE_GPS 고정**. 점별 판정 반환. `403 NOT_ASSIGNED_RIDER`, `409 LOCATION_MODE_MISMATCH, LOCATION_NOT_ACCEPTED_IN_STATE` |
| `POST …/arrive-destination` | `IN_TRANSIT → ARRIVED_AT_DESTINATION` (라이더 입력, GPS 거리는 기록만) |
| `POST …/complete` multipart `requestId, method(FACE_TO_FACE|NON_FACE_TO_FACE), photo, note?` | `{result: COMPLETED|ALREADY_COMPLETED, proofId, completedAt}` / `400 PROOF_PHOTO_REQUIRED, PROOF_PHOTO_INVALID(시그니처 판정)` / `409 INVALID_STATE, ALREADY_COMPLETED` |
| `POST …/release` `{reason}` | 픽업 전 배차취소. 이후 `409 SUPPORT_REQUIRED` |

### 위치 점 판정 (`LocationVerdict`)
`ACCEPTED_LATEST`(최신 반영) · `ACCEPTED_HISTORY`(최신보다 과거=역순, 저장만) · `STALE`(측정 후 P-06 초과 지연) · `DUPLICATE`(같은 seq) · `REJECTED_INVALID`(범위/NaN/음수 정확도/seq·시각 누락) · `REJECTED_FUTURE` · `REJECTED_ANOMALY`(P-07 초과 속도). 최신 위치는 `measuredAt` 이 더 새로운 정상 점으로만 갱신 — 역행 없음.

## 5. 고객 앱 → Delivery (`ROLE_CUSTOMER`)

- `GET /api/customer/orders/{orderId}/tracking` → `{orderId, deliveries:[CustomerDelivery], serverTime}`. 본인 주문 아니면 `404 ORDER_NOT_FOUND`(존재 여부 비노출).
  - `CustomerDelivery`: `status, statusLabel, locationMode, simulated, destination, riderLocationVisible, rider{latitude, longitude, accuracyMeters, measuredAt, receivedAt, source, ageSeconds}|null, eta{status: OK|UNAVAILABLE|NOT_APPLICABLE, etaSeconds, distanceMeters, computedAt, estimatedArrivalAt, provider, message}, packages{total, handedOver}, updatedAt, …, serverTime`
  - 라이더 위치는 정책 P-05 시점부터 종료 전까지만. 다른 고객 정보·라이더 전체 경로 미포함.
- `GET /api/customer/places?query=` — 카카오 Local 검색(서버 키). 실패 `503 KAKAO_*`.

## 6. 실시간 (STOMP over WebSocket `/ws`)

- CONNECT 헤더 `Authorization: Bearer <JWT>` 필수. SEND 금지.
- `/topic/orders/{orderId}`: 해당 주문 고객(또는 관리자)만 구독. 메시지 `{type:"DELIVERY_UPDATED", kind: STATUS|LOCATION|ETA|NOTE|CREATED, data: CustomerDelivery}`
- `/topic/riders/{riderId}`: 본인만. `OFFER_OFFERED/…_SUPERSEDED/…_EXPIRED`, `DELIVERY_UPDATED`(RiderDelivery), `ASSIGNMENT_REVOKED`
- `/topic/admin/deliveries`: 관리자
- 커밋된 변경만 전송. 클라이언트는 (재)연결 시 REST 로 재조회하고 `serverTime` 이 더 오래된 메시지는 버린다.

## 7. Delivery → OMS 이벤트 (Transactional Outbox, at-least-once)

공통 봉투: `{eventId, eventType, eventVersion: 1, sourceSystem: "DELIVERY", occurredAt, …}` — 전달 대상 `delivery.outbox.oms-endpoint`(local-test 미설정 시 Mock OMS `/mock-oms/internal/events`), 실패 시 지수 백오프(최대 60초).

| eventType | 필드 | 의미 |
|---|---|---|
| `DeliveryPickedUp` | deliveryId, orderId, riderId, pickedUpAt, packageIds | 배송 단위 전 포장 인수 |
| `DeliveryCompleted` | deliveryId, orderId, riderId, completedAt, proofId, deliveryMethod | **배송 1건** 완료. 주문 완료 판정은 OMS 규칙 |
| `DeliveryCancelled` | deliveryId, orderId, cancelledAt, beforePickup | 픽업 전 취소 |

## 8. 테스트 전용 (local-test 프로필에만 존재)

- `POST /api/auth/test-login` `{userId, passcode}` → `{accessToken, userId, role, expiresAt}`
- Mock OMS: `POST/GET /test-api/mock-oms/orders`, `GET …/orders/{id}`, `POST …/orders/{id}/cancel`, `POST …/deliveries/{id}/resend-request`, `GET …/inbox`, `PUT …/event-fault`
- Mock MFC: `GET /test-api/mock-mfc/packages`, `GET …/deliveries/{id}/packages`(QR 포함), `POST …/deliveries/{id}/seal`, `PUT …/fault`
- 관리: `/test-api/admin/deliveries[/{id}]`, `…/{id}/reassign`, `…/{id}/resume-dispatch`, `…/{id}/eta/recompute`, `/test-api/admin/riders|outbox|kakao-usage`, `PUT /test-api/admin/mfcs/{id}`, `GET /test-api/mfcs`, `GET /test-api/test-destinations`
- 시뮬레이터 제어(ADMIN): `SIMULATOR_RUNBOOK.md` §4 참조
