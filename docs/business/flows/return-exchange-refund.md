# S6 반품·교환·회수·검수·환불 사가

버전 0.1 · 2026-10-23 · 설계안. 조정자: `claim-service`. 근거: OMS 10~11장, A.1.12~A.1.13, A.5.5; Commerce 13장; MFC IV-10; Delivery 9.5; BackOffice BO-13/14.

## 0. 확정 정책

| 항목 | 규칙 |
|---|---|
| 접수 기한 | 일반 교환·반품: 실제 수령 후 **7일**. 신선식품(냉장/냉동): **24시간**. 하자·오배송·광고 상이 **교환**: 수령 후 **3개월** 또는 안 날(알 수 있었던 날)부터 **30일** 중 하나 |
| 기한 계산 | Asia/Seoul 실제 수령시각 기준, 경계시각까지 가능. 3개월은 달력 월(없는 날은 말일). 분할배송은 신청 품목의 실제 수령시각 |
| 주소 | 회수·재배달 주소는 원주문 주소 고정 |
| 비용 | 단순 변심 교환 왕복 9,000원 합계. 상품/배달 문제는 0원. 일반 반품 회수비·환불 개시점·거절품 처분 `[팀 확인 필요]` |
| 환불 금액 | 원주문 가격 스냅샷·품목별 할인 안분. 현재 가격 재계산 금지. `추가 환불 가능 한도 = 승인액 − 성공 누적 − 불명 점유` |
| PG | 부분 반품은 원결제 부분취소(토스페이먼츠). 전체 취소 후 재결제 방식 아님 |
| 재고 | 환불·회수 요청만으로 재고 복구 없음. 상온 미개봉 적격품만 검수 후 재판매. 신선·냉장·냉동 회수품 재판매 금지 |
| 재배송 | 교환 승인 건에만 허용. 원주문·교환 클레임에 연결, 새 주문·새 결제 생성 금지 |
| 중복 방지 | 반품 가능 수량 = 실제 전달량 − 완료/진행 반품 점유. 동일 주문 중복 교환 차단 |

## 1. 흐름

```
(1) 접수   Commerce/CS ─ POST claims(RETURN|EXCHANGE) {orderLineId, qty, reason, evidence, receivedAt}
           claim-service: 자격(기한·사유 특례)·대상 수량·중복·진행 점유 검증 → ClaimOpened
           · delivery-execution의 DeliveryCompleted(품목별 수령 시각)와 order 스냅샷 참조
(2) 심사   CS/운영자(BO-14) 또는 자동 규칙 → ClaimDecided(품목별 APPROVED/REJECTED, 회수 필요 여부)
           · 3건 중 2건 승인 → 2건만 후속. 회수 없는 처리는 승인 사유·증빙 필수(기본 원칙의 예외)
(3) 회수   claim ─ ReturnPickupRequested ─► delivery-dispatch: 회수 배송(type RETURN, 별도 배송번호, 원주문 연결) → 배차
           delivery-execution: 고객 수거 ReturnPickedUp → 점포 반납 ReturnDelivered
(4) 수령   mfc-inbound: 점원 수령 확인 → ReturnItemReceived → mfc-inventory H+q, Q+q (검수 대기; 판매 가능 재고 아님)
           · 회수 승인량 2 / 실수령 1 → 미수령 1 남김. 검수·PG 결과 선완료 금지
(5) 검수   mfc-quality: ReturnInspected(품목별 RESALE / DISPOSE) → mfc-inventory: RESALE → Q−q, E+q ; DISPOSE → 폐기 승인 후 H−q
(6) 환불   claim-service: 환불 개시점(회수 확인/센터 수령/검수 완료 중 등록된 정책; 미설정이면 심사 대기 상신, 자동 환불 없음)
           → RefundIntentCreated(원안분 금액) → payment.PartialCancel(원거래, 금액, 멱등키)
           ← RefundResult SUCCESS/FAILED/UNKNOWN → order OrderLineQuantityChanged(반품 수량), 고객 안내
(7) 교환   ClaimDecided(EXCHANGE) → ExchangeReshipmentApproved → order: 교환 이행 단위 생성(원주문 연결) → S2 재진입(새 피킹·패킹·배송; 새 출고 수량만 1회 차감)
           · 교환품 품절 → 고객 협의(환불 전환/대기). 자동 대체 금지
(8) 종결   ClaimClosed: 회수·검수·환불/재배송·고객 안내 의무 모두 확인. 철회는 진행된 Delivery 수행·실물·PG 취소를 대사해 처리(사건 삭제 금지)
```

## 2. 환불 금액 예시 (OMS 10.2)

A 20,000 + B 10,000, 쿠폰 3,000(비례 안분, 원 미만 버림, 잔여 원은 최대 금액 품목), 배달팁 0(30,000 이상), 봉투 100
→ 원결제 27,100. B 1건 반품 → B 원안분 환불 9,000, 잔액 18,100. 누적 성공/진행/불명을 제외한 잔액만 새 취소 가능.

## 3. 상태 축

| 축 | 상태 |
|---|---|
| 클레임 | OPENED → UNDER_REVIEW → DECIDED(일부 승인 가능) → IN_PROGRESS → CLOSED / WITHDRAWN |
| 회수 | REQUESTED → DISPATCHED → PICKED_UP → DELIVERED_TO_STORE → RECEIVED → INSPECTED |
| 환불 | 접수 → 심사/승인 → 실행 대기 → PG 결과 확인 → 완료 / 실패 / 확인 중 |
| 교환 재배송 | APPROVED → FULFILLMENT_CREATED → (S2 상태) |

## 4. 분기

| 상황 | 처리 |
|---|---|
| 기한 경계(7일째 23:59 Asia/Seoul) | 접수 가능 |
| 신선식품 24h 경과, 사유=하자 | 특례(3개월/인지 30일)로 심사. 일반 기한으로 거절 금지 |
| 인지 시각 불명 | 증빙 보완 심사 |
| 환불 성공·쿠폰 복원 실패(3차) | 환불 유지, 복원만 별도 재처리 |
| PG 응답 유실 | 같은 환불 요청 ID 조회. 새 금액 취소 중복 실행 금지 |
| 완료 후 파손 신고(Delivery 9.3) | 기존 DeliveryCompleted 유지, 귀책 조사(case)와 고객 대응(환불/교환) 분리. 라이더 공제는 환불과 동일 처리 아님 |
| 고객 측 사유(부재·주소 오류) 회수 | 회수 후 취소·환불. 재주문은 새 주문(재배송 아님) |
| PMart 측 사유(파손·오배송) | 회수 + 교환 재배송 또는 회수 + 취소 환불 |

## 5. 운영자(BO-13/14) 경계
- 환불 의도는 claim/order가 산정, PG 실행은 payment. BackOffice 독자 PG 명령 금지.
- 회수·검수·환불 완료를 각각 표시. "검수 합격=재판매 가능=환불 완료"로 합치지 않는다.
- 비용 결정(회수비·교환비)은 사건에 근거·승인자와 함께 기록. 지급·수납은 회계 연계 `[결정 필요]`.
