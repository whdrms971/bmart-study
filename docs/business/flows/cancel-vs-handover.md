# S3 취소 접수 vs 현장 중지·인계 경합 사가

버전 0.1 · 2026-10-23 · 설계안. 조정자: `order-orchestration-service`(접수는 `claim-service`). 근거: OMS 8.3~8.5, 10, 12, A.1.11, A.5.4; MFC IV-08, 인계 fence(1980~1986행); BackOffice BO-12; Delivery 5.4~5.5.

## 0. 원칙

1. **취소 접수시각만으로 인계보다 우선했다고 판단하지 않는다.** 같은 수량에 대해 현장(`mfc-fulfillment`)이 "출고 중지 확정" 또는 "인계 확정" 중 하나를 먼저 확정하고, 후속 요청은 최신 상태로 재심사한다.
2. 실제 인계된 수량은 출고 전 취소 대상이 아니다. 회수·반품 절차(S6)로만 처리한다.
3. 취소 요청 상태 축(접수→출고 중지 확인 중/인계 확인 중→취소 처리 중→취소 완료/취소 불가)은 주문의 상품준비/배송 상태와 **별개**다.
4. 취소 완료·환불 완료·재고 복구는 각각 별도 사실이다. PG 취소 성공 전 "환불 완료" 표시 금지.

## 1. 취소 가능 시점 (문서 기준)

| 시점 | 가능 여부 | 비고 |
|---|---|---|
| 결제 전(초안) | 자유 | 사가 S1 보상 |
| 결제 후 ~ 피킹 전 | 고객 자율 취소 | 정확한 마감·CS 승인선 `[팀 확정 필요]` |
| 피킹·패킹 중 ~ 인계 전 | 현장 중지 확인 후 | 포장별 판정 |
| 인계 후(라이더 보유) | 출고 전 취소 불가 → 회수·반품 | Delivery 참고: 회수비 부담 정책 `[결정 필요]` |

## 2. 흐름

```
Commerce/CS ─ POST claims(CANCEL) ─► claim-service: 자격·대상 수량(원수량 − 기취소 − 인계 − 진행 클레임) 심사
  → CancellationRequested → order-orchestration.start(S3, stopRequestId)

S3 단계
  a. mfc-fulfillment.StopWork(orderId, lines, stopRequestId)   [동기, 멱등]
     ← WorkStopResult perPackage: STOPPED | HANDOVER_IN_PROGRESS | HANDED_OVER
       · fence가 HANDOVER_IN_PROGRESS이면 "인계 확인 중" 대기 → 결과(HANDED_OVER 또는 fence 만료 후 STOPPED) 재조회
       · 응답 불명 → QueryHandoverResolution(stopRequestId)로 포장별 판정 조회. 신규 취소·해제 반복 금지
  b. delivery-dispatch.CancelAssignment(deliveryId)  [해당 배송의 모든 포장이 STOPPED일 때만]
     ← 픽업 전 → DeliveryCancelledBeforePickup / 픽업 후(인계됨) → 취소 불가, 회수 경로
  c. mfc-inventory: STOPPED 수량의 예약 해제·실물 복귀 확인
     · 피킹 전: R−q, A+q
     · 피킹 후: E−q, R−q → CANCEL_RETURN_PENDING → 복귀·적격 확인 후 E/A+q (검증 없이 판매가능 복구 금지)
  d. order-service: 취소 확정 수량 반영(OrderLineQuantityChanged, scope FULL/PARTIAL), 미인계 잔량·인계량 분리
  e. claim-service: 환불 의도(원가격·원안분; 봉투값은 전체 취소만 환불; 배달팁은 확정 기준) → payment.Cancel/PartialCancel(멱등키)
     ← RefundResult SUCCESS → 취소 완료 안내 / FAILED → 재심사 / UNKNOWN → 원거래 조회(1분×5→상신→15분)
  f. (3차) 쿠폰 복원: PG 취소 성공 확인 시각에 원유효기간 남아 있으면 1회 요청. 만료 연장·신규 발급 없음
  g. HANDED_OVER 수량이 있으면 → S6(회수·반품)으로 분기, 고객 안내
```

## 3. 경합 사례

| 사례 | 판정 |
|---|---|
| 포장 3개 중 2개 인계·1개 미인계, 전체 취소 요청 | 1개만 출고 전 취소, 2개는 회수 심사. 전량 재고 복구·전액 환불 자동 확정 금지 |
| 취소 접수 성공, 현장 중지 응답 불명 | "출고 중지 확인 중" 유지. 고객에게 완료 표시 금지. 원요청 조회 |
| 중지 확정 후 늦은 QR 스캔 | fence가 없으므로 `AuthorizePackageHandover` 거절(INVALID_STATE). 라벨 무효 |
| 인계 fence 중 취소 요청 | 인계 확인 중 → fence 결과 대기. 취소가 먼저라고 인계를 되돌리지 않음 |
| 현장 중지·재고 복귀 완료, PG 취소 불명 | 주문은 취소 처리 중. 환불 완료 안내 금지. 재판매는 재고 복귀 확인으로 가능(PG와 독립) |
| 라이더 픽업 후 고객 취소 | 출고 전 취소 불가. Delivery 실제 인수 상태 확인 → 회수. 라이더 수행료는 환불과 독립(Delivery 5.5) |
| 탈퇴 접수 계정의 진행 중 취소 | 기존 주문 의무로 계속 처리. 탈퇴 완료는 의무 종결 후 |

## 4. 운영자(BackOffice BO-12) 가능 동작

- 조회: OMS 적격 판정, MFC 포장별 판정, Delivery 인수 상태, PG 결과를 **원천별 시각과 함께** 표시.
- 명령: 원결과 재조회 / 미전달 재전송 / 승인된 보상 실행. "강제 취소 성공", "예약 강제 해제"는 없다.
- 환불 한도: `추가 가능액 = 승인액 − 성공 누적 − 불명 점유` 초과 요청 차단(payment가 강제).

## 5. 종결 조건 (A.4.3)

- 주문행 수량 대사 일치(미이행 + 인계 누계 + 출고 전 취소 누계 = 원수량)
- 예약 해제·실물 복귀가 mfc-inventory commitId로 확인
- PG 취소 결과 확정(불명 아님)
- 배차 취소/회수 의무 결정
- 고객 안내 전송(실패 시 통지만 재시도)
