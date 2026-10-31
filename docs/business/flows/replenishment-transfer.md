# S5 보충 계획 → 사업승인(TRF) → CDC 출고 → 간선 운송 → MFC 수령 → Transit 대사

버전 0.1 · 2026-10-23 · 설계안. 사업 결정 조정자: `replenishment-service`. 실행은 각 정본이 확정. 근거: SCM 3~17장·SCM-D01~D12·전체 연결 사례; WMS CDC-F-014~027; TMS-D01~D11·전체 연결 사례; MFC IV-02/06, WK-14; BackOffice BO-18/19.

## 0. 불변 제약

- **1요청·1이관(TRF)·1출발.** 같은 TRF의 후속 분할 출발 금지. 용량 부족이면 다른 차량/회차 또는 별도 요청·승인.
- 승인 ≠ 확보 ≠ 출발 ≠ 수령 ≠ 사용 준비. 각각 다른 정본의 결과.
- 출발 후 목적지 변경·CDC 반송·원TRF 취소 없음(1차).
- SCM은 재고 수량을 직접 바꾸지 않는다(`직접 수량 전기 0`).
- 예정 공급으로 고객 주문을 예약판매하지 않는다.

## 1. 계획 (replenishment-service, SCM-D01~D05)

```
입력 스냅샷(planRunId, asOf, 원천 버전):
  mfc-inventory  InventorySnapshot → H(적격 현물, 예약 차감 전), 격리 제외
  order-service  CustomerCommitmentChanged → C(유효 미이행 약속, BLOCKED 포함)
  replenishment  기존 공급 계보 → I(기한 내 사용준비 가능한 미수령 잔량), P(t)(기수령 준비대기)
  demand-planning ForecastVersion → F = max(0, 남은 전체예측 − 예측에 포함된 C)
  정책 → SS(안전량), 보호기간, 배수 m

계산: S = F + SS ; IP = H − C + I (+P(t)) ; N = max(0, S − IP)
      W = m·ceil(N/m) ; B = min(CDC 신규공급가능량, MFC 추가수용량, TMS 상한) ; K = m·floor(B/m) ; Q = min(W, K)
      원수요 미충족 = max(0, N − Q), 배수 완충 = max(0, Q − N)
      시간대별 최초 부족시각·최대 부족·회복시각 별도 표시
출력: ReplenishmentRecommended (요청·승인 아님). 조회 실패는 H0/SS0로 대체하지 않음 → INPUT_PENDING
```

## 2. 요청 (mfc-inbound WK-14 → replenishment SCM-D06)

```
MFC 관리자: 추천 확인 → 수량·희망 사용준비 시각·일반/긴급·사유 입력 → SupplyRequestSubmitted(로컬 DRAFT→SUBMITTED)
replenishment: 권한·센터·단위·배수·기한 의미 검증, 같은 원수요의 미해결 요청 중복 검사 → SupplyRequestReceived(ACCEPTED/CONFLICT/PENDING)
  · 같은 키 다른 본문 = 충돌. 고객 구매 상한 10개는 센터 간 공급에 적용하지 않음.
```

## 3. 배분·사업승인 (replenishment SCM-D07/D08 + approval-service)

```
replenishment: 같은 CDC 재고를 두고 센터별 요청 비교 → 배분 후보(정책 미승인 시 수동 심사) → 심사 대상 등록
approval-service: ChangeRequest(공급 승인) → 승인자 권한·대행·자기승인 분리·추가승인 조건(기존 50% 규칙은 정책 등록 시만) 검사
  → ApprovalDecided(APPROVED/PARTIALLY_APPROVED/REJECTED, approvalId)
replenishment: 승인 정본 확정 → TransferApproved(trfId, trfVersion, lines{approvedQty}, deadlineMeaning, dueAt, approvalRef)
  · 요청 100 / 승인 60 → 미승인 40 사유 기록. 승인 결과는 replenishment만 생성(approval은 참조).
```

## 4. 실행 (SCM-D09; 분산 부분 성공 허용)

```
TransferApproved ─► cdc-outbound: TRF 인수 → cdc-inventory.Allocate(FEFO 다LOT) → AllocationResult(allocated 48 / short 12)
               ─► transport-planning: TMS-D01 접수 → 후보(직송/순회/회차) → S8 자원(fleet 차량·기사, cdc-location 도크, mfc-capacity 하차면) → TripAssigned
               ─► mfc-inbound: InboundPromise(EXPECTED)
replenishment: 확보/편성 결과 수집 → READY | PARTIALLY_ALLOCATED | TRANSPORT_PENDING | EXECUTION_HOLD
  · WMS 48 확보 / TMS 차량 없음 → 재고는 확보 권리 유지, 운송 미편성. 새 TRF 48을 만들어 재확보하지 않음.
  · 승인 60 / 확보 48 → 사업승인을 48로 덮지 않음. 승인미확보 12 별도 추적.
```

## 5. 출고·실제 출발 (cdc-outbound → transport-execution → cdc-inventory)

```
cdc-outbound: Wave → 실피킹 → 분류/검품(BOX SEALED/PLT READY, LPN) → 출발 준비 적격(TMS 창·MFC 수령창 소비) → 가상 상차 LOADED (≠출발)
  · 불변식: 상차 ≤ 검품합격 ≤ 분류 ≤ 실피킹 ≤ 유효할당 ≤ 승인
transport-execution: 출발 전 실상차 대조(타 MFC 혼입·누락·봉인·구획) → 실제 발차 증빙 → TruckActuallyDeparted(departureId, source REAL/SIMULATED, trfCompositions)
cdc-outbound: 대조 → TruckDepartureValidated → cdc-inventory.PostDeparture
cdc-inventory: TOUT 1회 전기  CDC 현물 −q · 예약 −q · Transit +q  (한 거래) → TransferDeparted(trfId, departureId, lines)
  · 한 차량에 TRF A/B 합차: 차량 출발 1회, TRF별 TOUT 각각. A 성공/B 불명 → B만 원결과 조회, A 재차감·B 재출발 금지.
  · 승인 60 / 실출발 48 → 출발 구성 48 동결. 미출발 12는 같은 TRF로 보내지 않음 → SCM-D10 잔량 정리(수동 승인) → TransferResidualClosed(60 = 48 + 12)
cdc-outbound: TransferShipmentManifestIssued → mfc-inbound, transport-execution
```

## 6. 운송·도착·인계 (transport-execution, TMS-D05/D08/D09)

```
운행 IN_TRANSIT, 정차별 PLANNED→ARRIVED→WAITING→UNLOADING→HANDOVER_PENDING→HANDOVER_RECORDED→DEPARTED
도착: StopArrived (수량 증거 아님) → mfc-inbound InboundArrivalRecorded
하차·인계: TransportHandoverEvidenceRecorded(정차·TRF·LPN·수량·인계자/인수자·사진) — TIN 아님
사건: 지연→TransportEtaUpdated(최초 약속 보존), 온도→TemperatureExcursion→quality, 고장→안전 보관·case 이관(환적·재출발 자동 없음)
마지막 정차 후 복귀 → TripPhysicallyEnded → fleet 재가용(복귀 + 준비 시간; 기사 가용은 별도)
```

## 7. 수령·TIN·Transit 대사 (mfc-inbound → mfc-inventory → cdc-inventory)

```
mfc-inbound: RECEIVE 작업, 실계수 → ConfirmReceipt(receiptId, lines{normal 44, quarantined 2, pending 0}, departureId)
mfc-inventory: TIN 전기 H +46 (정상 44 → 검수·적치 후 E+44 ; 격리 2 → Q) commitId → MfcReceiptPosted
cdc-inventory: 같은 receiptId 1회 확인 → Transit −46 → 미정산 t = 48 − 46 = 2 → TransitReconciled(state PENDING) / TransferAckStatus
  · 불변식: 출발 q = MFC 확정수령 r + 승인 운송손실 l + 미정산 t ; r = 정상 n + 격리 h
  · TIN 성공·CDC ACK 유실 → "수령 전기 완료/CDC 확인 대기". MFC 재입고·새 TRF 금지. 원receiptId로 복구.
  · 미수령 2 → 조사(차량 잔류/상차 누락/분실). 자동 손실 전기 금지. 손실 승인(본사)은 별도 → cdc-inventory l 반영 → q = r + l + t 종결.
replenishment: 공급 계보 갱신(I에서 수령분 제거, 격리 2는 H/I 제외, 미수령 2는 기한 내 사용준비 가능 여부로 I 판정) → 필요 시 SCM-D11 추가 공급 재계산(별도 요청/승인)
```

## 8. 변경·취소 경계 (SCM-D10, TMS-D06)

| 시점 | 허용 | 확인 |
|---|---|---|
| 미승인 | 요청 새 버전·철회 | 원요청 이력 유지 |
| 승인·미확보 | 승인 변경/취소 심사 → TransferChanged/Cancelled | WMS/TMS 실제 미실행 확인 |
| 확보·작업 중 | 중지·잔량 정리 후 변경 | SCM이 예약 직접 해제·실물 삭제 금지 |
| 상차·미출발 | WMS 하차/복귀·TMS 배정 대사 | 출발 경합·불명은 결과 조회 |
| 실제 출발 후 | 사실 보존·기한/공급 영향·미결 관리 | 미출발 취소·목적지 전환·CDC 반송 금지 |
| 수령·준비 후 | 실제 결과·차이·품질/과잉 대응 | 원수령 취소로 CDC 복원 금지 |

## 9. 수용 검증 발췌 (SCM ST-xx, TMS TT-xx)

| 조건 | 기대 |
|---|---|
| H18/C6/I24/F72/SS18 | S90, IP36, N54 |
| N54/m12/B48 | W60, Q48, 원미충족 6 |
| 승인/확보/출발 각 24 | 같은 공급 I 최대 24(72 아님) |
| 승인 60·실출발 48 | 미출발 12 별도, 같은 TRF 후속 출발 0 |
| 출발 12·정상 8·격리 2 수령 | TIN 10, 대사 후 Transit 2, 격리 2 별도 |
| TIN 성공·CDC ACK 불명 | 수령 완료/ACK 대기, MFC 재입고 0 |
| 같은 TRF 3회 전송 | 운송 의무 1건 |
| 총중량 적합·냉동 구획 부족 | 편성 불가 |
| 실차 같은 시간 두 운행 | 확정 최대 1개 |
| 승인 없는 추천 | 운송 실행 0 |
