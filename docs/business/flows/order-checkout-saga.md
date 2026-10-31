# S1·S2 주문 결제 진입 → 확정 → 이행 사가

버전 0.1 · 2026-10-23 · 설계안. 조정자: `order-orchestration-service`. 근거: OMS 4~9장, A.1.4·A.1.7·A.1.9·A.1.15, A.5.1~A.5.3; Commerce 10~12장; MFC IV-05/07/08, WK-14; Delivery 3·5장.

## 0. 참여자와 정본

| 참여자 | 확정하는 것 |
|---|---|
| order-service | 초안·견적·주문 상태·스냅샷·이행 계획 |
| order-orchestration | 사가 단계·타임아웃·보상 로그 |
| mfc-inventory | 재고 예약 R(HELD→CONFIRMED→CONSUMED) |
| mfc-capacity | 작업 여력 CapacityHold |
| delivery-capacity | 도착 슬롯 점유(예약배달) |
| payment | PG 승인·불명·실패·보상 취소 |
| mfc-fulfillment | 작업 수락·준비 시작·결품·포장·인계 |
| delivery-dispatch / execution | 배차·픽업·전달 |

## 1. 사가 S1: 초안 → 자원 확보 → PG → 확정

```
Commerce ─(1) POST drafts {purchaseIntentId, requestId}─► order-service
order-service: 회원·판매상태·구매상한(Σ qty×unitsPerSku ≤ 10)·권역/MFC·슬롯·중량 검증, pricing 견적, 배달팁/봉투값/최소 15,000원 판정
             → draft(quoteVersion, quoteExpiresAt = now+10m) 스냅샷 저장, OrderDraftCreated
Commerce ─(2) POST drafts/{id}/checkout─► order-service ─► order-orchestration.start(S1)

S1 단계 (각 단계 동기 명령 + 결과 이벤트/조회, 모두 requestId 멱등)
  a. 가격 동결·초안 유효 확인 (quoteExpiresAt)                          — 만료 → RE_QUOTE_REQUIRED (재동의)
  b. mfc-inventory.ReserveInventory(orderId, lines, ttl=10m)          — 전품목 성공만 통과. 일부 성공 → 성공분 해제 후 FAIL
  c. mfc-capacity.HoldCapacity(orderId, window)                       — 실패 → b 보상
  d. delivery-capacity.HoldSlot(orderId, window)  [예약배달만]           — 실패 → b,c 보상. 미설정/조회 실패 → CONFIG_REQUIRED 보류(0/무제한 아님)
  e. (3차) promotion.HoldCoupon / 행사 한도                              — 1차 생략. 첫 구매 자격 점유는 order-service 내부
  f. payment.StartPayment(orderId, amount, paymentAttemptId)           — PG 결제창 진입 정보 반환
  g. payment 승인 결과 대기: PaymentApproved | PaymentFailed | PaymentResultUnknown | PaymentMismatch
  h. 승인·금액·통화·주문 참조 일치 확인 → order-service.Confirm(orderId, version)
  i. 확정 성공 → inventory.ConfirmReservation, capacity.ConfirmHold, slot.ConfirmHold → OrderConfirmed
  j. 확정 실패(저장 실패·운영 제한 발생) → payment.CompensationCancel(원거래) 결과 확인 → 자원 해제
```

### 1.1 타이머 규칙

| 타이머 | 시작 사건 | 만료 처리 |
|---|---|---|
| 가격 동결 10분 | 초안 생성 | 재견적·고객 재확인. 자동 해제·환불 아님 |
| 재고 예약 10분 | ReserveInventory 성공 | 원거래 조회 → 미승인/실패 확정이면 해제·`OrderExpired`. **불명이면 `결제 확인 대기 점유`로 전환**(해제 금지) |
| PG 불명 조회 | 승인 불명 | 1분 간격 5회 → `case-service` 상신 → 15분 간격 후속 조회. 수동 종결도 PG 원거래 증거 필수 |

PG 진입 전 두 타이머 모두 유효해야 하며 더 이른 만료를 고객에게 안내한다.

### 1.2 분기

| 상황 | 처리 |
|---|---|
| 같은 purchaseIntentId+requestId 재전송 | 기존 결과 반환. 같은 키 다른 본문 → `IDEMPOTENCY_CONFLICT` |
| 다품목 중 1품목 예약 실패 | PG 차단, 성공분 예약 해제 확인(이벤트 `InventoryReleased`) |
| 마지막 1개에 두 주문 | 예약 성공 주문만 PG. 결제 완료 순으로 승자 변경 없음 |
| 승인 성공·주문 저장 실패 | 영속 결제시도·원거래로 복구 시도. 불가 시 승인취소 보상. 새 주문·재승인 금지 |
| 승인액 ≠ 청구액 | `PaymentMismatch` → 확정 차단, `ReconciliationRequired`, 강제 출고 금지 |
| 예약 소멸 후 지연 승인 | 재확보 가능성 확인 → 복구 또는 승인취소. 승인취소 성공 전 재판매 금지 |
| 운영 제한(접수 중지) 발생 | 확정 전이면 차단·보상. 확정 후면 기존 약속 유지, 준비 보류 조사 |
| 주류 포함 | 토스페이먼츠 결제 인증 결과 확인. 자체 성인인증·현장 신분증 없음 |

### 1.3 보상 원칙
- 이번 시도에서 **새로 확보한** 예약·Hold·Slot만 해제한다(보상 로그에 resourceId 기록).
- 해제 응답 불명은 원예약 조회로 확인. 확인 전 "해제 완료" 표시 금지.
- PG 승인 후 보상은 `CompensationCancel`이며 그 결과(`CompensationCancelResult`)까지 대사해야 사가 종료.

### 1.4 구현 상태 (2026-10-31, 슬라이스 4·5 — a~j)

| 단계 | 구현 | 비고 |
|---|---|---|
| (2) 결제 진입 | order `POST /order-drafts/{id}/checkouts` | 견적 버전·10분 동결·차단 사유 확인 → orderId 발급(같은 진입 요청은 같은 ID) → orchestration 시작. 진행 중이면 기존 주문 ID 반환 |
| 시작 | orchestration `POST /internal/checkout-sagas` 202 | 사가만 저장하고 단계는 비동기(동기 체인 2 hop 규칙). 같은 초안의 진행 중 사가는 하나 |
| a | VALIDATE_QUOTE | 동결 경과 → RE_QUOTE_REQUIRED |
| b | RESERVE_INVENTORY | mfc-inventory 전부 또는 전무, TTL 10분, 부족 → INSUFFICIENT_STOCK, 취급 OFF → SKU_NOT_HANDLED |
| c | HOLD_CAPACITY | mfc-capacity 1시간 창(예약배달은 도착 슬롯, 즉시는 지금 시간 KST). LAN 시험값 주문 1건=1단위·시간당 30 |
| d | HOLD_SLOT | 예약배달만(그 밖 SKIPPED). delivery-capacity 정원 20 |
| e | (쿠폰) | 1차 생략 |
| f | START_PAYMENT | payment 결제 시도 생성, `resourceExpiresAt` = 자원 만료·가격 동결 중 이른 것 → 사가 WAITING_RESULT, 초안 `nextAction=PAY` 와 결제창 정보 |
| g | AWAIT_PAYMENT_RESULT | 결제 사건(order.payment.v1)은 깨우는 신호, 정본은 결제 시도 조회. 승인 → 보상 로그에 PAYMENT_APPROVAL. 실패 → 즉시 해제. 불명 → UNKNOWN_TRACKING. 불일치 → RECONCILIATION_REQUIRED |
| h | RECONCILE_PAYMENT | 승인액·통화 = 청구액(KRW). 다르면 RECONCILIATION_REQUIRED + `ReconciliationRequired`(확정·취소·해제 모두 멈춤, 강제 출고 금지) |
| i | CONFIRM_RESOURCES | 재고 예약·여력·슬롯 HELD → CONFIRMED(키 `sagaId:CONFIRM:자원`). **흐름 원문(h 주문 확정 → i 자원 확정)과 순서를 바꿨다**: `OrderConfirmed` 는 자원 확정 뒤 발행(사건 카탈로그 조건) |
| (h) | CONFIRM_ORDER | order `/internal/orders/{id}/confirmation` → 주문 저장·`OrderConfirmed` → 사가 COMPLETED. 금액 불일치 422 → 대사 |
| j | 보상 취소 | 확정 실패(점유 소멸 RESERVATION_LOST·주문 확정 거절 ORDER_CONFIRM_FAILED) → payment 보상 취소(전액) → **CANCELLED 확인 뒤에만** 재고·여력·슬롯 해제 → COMPENSATED |

- 단계 명령 Idempotency-Key = `sagaId:단계`, 해제 = `sagaId:RELEASE:자원종류`. 중단·불명이면 sweep(10초)이 30초 멈춘 사가를 같은 키로 이어서 진행한다.
- 거절 → 보상 로그의 자원만 역순 해제 → 원예약 조회로 RELEASED·EXPIRED 확인 뒤에만 RELEASED → 모두 확인되면 COMPENSATED. 초안은 `DRAFT`·`REVIEW` + `checkoutFailure`(견적이 유효하면 다시 결제 진입 가능, 새 주문 ID).
- 결제 대기 중 sweep 이 30초마다 결제 시도를 조회한다(사건 유실 대비). 미승인(CREATED·PENDING)은 자원 만료 뒤에만 해제(`RESOURCE_EXPIRED`), 조회 실패면 해제하지 않고 다시 확인. payment 는 `resourceExpiresAt` 뒤 승인 API 를 부르지 않는다. 불명 1분×5 재조회·상신은 payment 가 하고(`PaymentResultUnknown.escalated`), case 사건 연결은 슬라이스 9.
- 각 정본도 자체 TTL 로 HELD 를 EXPIRED 처리한다(재고·여력·슬롯). 초기 재고는 INITIAL_STOCK 승인 → mfc-inbound 초기 보유 반입 → mfc-inventory 전기(D2).

## 2. 확정 후: 이행 요청 (S2 시작)

```
OrderConfirmed → order-orchestration → mfc-fulfillment.ConfirmOrder(orderId, fulfillmentId, lines, window, outOfStockPreference, lotConstraints)
  ← WorkAccepted (작업 접수 ≠ 피킹 완료) | WorkRejected(재고 불일치/LOT 부적격/운영 중단/여력 부족)
WorkRejected → 같은 MFC 내 적격 재고 복구 가능? → 새 작업 버전 재개 : 고객 선택(보류/취소·정산). 타 MFC 재배정 없음.
mfc-fulfillment: PreparationStarted → order-service `상품준비`(이후 예약 시간 변경 차단)
mfc-fulfillment: 피킹 → 패킹 → PackingFinished → ShipmentReady(포장·봉투·중량·주류)
```

### 2.1 결품(LineShortageReported) 처리

| 고객 사전 선택 | 처리 |
|---|---|
| `품절 상품만 취소` | 미인계 범위에서 해당 품목 부분 취소 → claim.RefundIntent(원가격·원안분) → payment.PartialCancel → RefundResult → OrderLineQuantityChanged. 남은 주문이 15,000원 미만이어도 유지. 재결제 요구·배달팁 추가 없음 |
| `전체 취소` | S3(취소) 호출. 인계된 포장이 있으면 그 수량은 회수 절차 |
| 선택 없음/불명 | 출하 보류 → `case-service` CS 이관. 임의 대체 상품 금지 |

부분 취소 PG 요청은 `추가 환불 가능 한도 = 승인액 − 성공 누적 − 불명 점유` 안에서만, 같은 요청 ID로 1회.

## 3. 배차·인계·전달

```
ShipmentReady → delivery-dispatch: DeliveryCreated(배송 단위 = order 이행 계획; 10,000g 초과 분할은 order-service가 결정)
  → 후보 탐색(rider-service 적격: 운행중·신규배차 ON·권역·수단·제한 없음) → DispatchOfferSent(1분) → DeliveryAssigned(동시 수락 1명)
라이더 가게 도착(RiderArrivedAtStore) → QR 스캔(포장 전수)
  → mfc-fulfillment.AuthorizePackageHandover(packageId, riderId, deliveryId)  ; 배정 일치·기인계·타 주문·제한 라이더 검사 → HANDOVER_IN_PROGRESS(fence)
  → mfc-fulfillment.CommitPackageHandover → mfc-inventory.CommitPackageHandover(H−q, E−q, R−q, commitId)
  → PackageHandedOver(inventoryCommitId) → order(인계 누계), delivery-execution(PickupCompleted 조건), claim(출고 전 취소 불가 범위)
고객 도착 → 사진 업로드 성공 → 전달 완료 버튼 → DeliveryCompleted(증빙 ref)
  → order: 배송 단위 완료 집계 → 모든 유효 배송 완료 시 OrderCompleted (시간 경과 자동완료 없음)
  → review 자격 시작(품목별 수령 시각), notification(분할은 전량 완료 1회), demand-planning 실적
```

### 3.1 수량 불변식 (order-service가 검증)
`주문행 원수량 = 미이행 보유 + 유효 인계 누계 + 출고 전 취소 누계`. 인계 후 반품은 별도 회수/검수/환불 수량(과거 인계 삭제 금지).

### 3.2 분기
| 상황 | 처리 |
|---|---|
| 배차 실패(기사 없음·반복 거절·만료) | `DispatchFailed` → 점포·CS 안내, 고객 취소 시 S3. 배차 불가로 PMart 측 취소면 환불(쿠폰 보상은 3차) |
| 픽업 전 배차 취소 | 상품 점포 보관, 재배차. 인계 fence 해제 |
| 픽업 후 배차 취소 | `case-service` 지원센터: 상품 위치·회수·재인계 확인 후 처리 |
| 전달 실패 | 자동 환불 없음. 고객 연락·보관·회수 판단(case). 재배송은 교환 승인 건만 |
| 완료 후 파손·누락 신고 | 기존 `DeliveryCompleted` 유지, `DeliveryIncidentRaised` + claim 접수 |

## 4. 수용 검증 (문서 OMS 14장 발췌)

| 조건 | 기대 |
|---|---|
| PG 승인·OMS 확정 응답 유실 | 원주문 조회/복구, 신규 주문/재승인 0 |
| WMS 예약 성공·MFC 슬롯 실패 | PG 차단·원예약 해제 확인 |
| 10분 만료와 PG 결과 불명 | 신규 결제·출고 차단, 취소/거래 확인 없는 재판매 없음 |
| 10,000g / 10,001g | 전자 미분할, 후자 2배송. 1배송 완료는 부분 전달 |
| 부분 품절 | 원가격·할인 안분으로 PG 부분취소 1회, 잔여 주문·누적 환불 한도 유지 |
| 취소와 포장 인계 동시 | 먼저 확정한 상태 기준, 이중 재고 복구 없음 (→ S3) |
