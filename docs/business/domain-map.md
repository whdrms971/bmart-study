# 도메인 맵 (문서 → 도메인 → 서비스)

> 비즈니스 문서 8개가 CLAUDE.md의 서비스 그룹 8개에 어떻게 대응되는지, 그리고 문서 용어와 서비스 용어의 차이를 정리한다.

## 1. 문서 ↔ 서비스 그룹

| 비즈니스 문서 | 버전·상태 | 서비스 그룹 (CLAUDE.md) | 주의 |
|---|---|---|---|
| Commerce-business-logic.md | 날짜 미기재, 3,668행 | 1. Commerce (9) + Platform(auth, notification) | 2026-10-20 책임 정리 **이전** 문서. PPC=MFC, DC=CDC, "TMS가 고객 배송"·"WMS가 PPC 재고" 서술은 TMS/Delivery/WMS 최신 문서로 대체 |
| OMS-business-logic.md | 날짜 미기재, 1,014행 + 부록 A | 2. Order & Payment (5) | 부록 A가 하위 모듈 15개를 정의. 본문 "6개 분류"(L449)와 "8개 분류"(L822) 표기 불일치 |
| SCM-business-logic.md | v1.1, 2026-10-20 | 3. SCM (4) | 문서는 supplier/procurement를 **1차 제외**. CLAUDE.md는 목표 구성으로 포함 |
| WMS-business-logic.md | v8.1, 2026-10-20 | 4. CDC WMS (5) | CDC 전용. MFC용 WMS 없음. CDC 외부 입고/ASN 1차 제외 |
| MFC-business-logic.md | v2.0 통합 2026-10-23 | 5. MFC (6) | "WMS 재고 책임 모듈"의 MFC 몫 = `mfc-inventory-service` |
| TMS-business-logic.md | v1.1, 2026-10-20 | 6. Middle-mile TMS (5) | 간선만. 라이더·고객 배송은 Delivery |
| Delivery-business-logic.md | Draft, Notion export 2026-10-19 | 7. Last-mile Delivery (6) | 외부 브랜드(B마트·배민커넥트) 참고값 다수. 확정 정책 아님. 이미지 링크 깨짐 |
| BackOffice-business-logic.md | v2.0, 2026-10-21 | 8. Platform & Support (6, config·discovery 제거) + **추가 제안** approval/case/center-master | `backoffice-bff`만으로는 승인·사건 원장을 담을 수 없음 (ADR-0004) |

## 2. 용어 대응

| Commerce/Delivery 문서 표기 | 표준 표기 | 서비스 |
|---|---|---|
| PPC, 점포, 매장 | MFC | `mfc-*` |
| DC, Warehouse | CDC | `cdc-*` |
| MFC Operations | MFC | `mfc-fulfillment`, `mfc-capacity` 등 |
| WMS(DC+PPC 재고) | CDC WMS + MFC 재고 | `cdc-inventory` / `mfc-inventory` |
| TMS(고객 배송 포함) | TMS(간선) + Delivery(고객 배송) | `transport-*` / `delivery-*` |
| 관제, ICS, 본사 | BackOffice | `backoffice-bff`, `approval-service`, `case-service` |
| Payment, 결제 처리, 토스페이먼츠 | payment-service(토스페이먼츠 어댑터+거래 원장) | `payment-service` |
| 점원앱, PDA앱, 패킹앱 | MFC 현장 단말 | `mfc-fulfillment` API |
| 라이더앱, 배민커넥트 | 라이더 단말 | `delivery-dispatch`, `delivery-execution` API |
| 배차 서버, 배송 서버 | Delivery 도메인 | `delivery-dispatch`, `delivery-execution`, `delivery-tracking` |

## 3. 문서 간 충돌과 적용 우선순위

우선순위: **CLAUDE.md 서비스 목록 > 2026-10-20 이후 문서(WMS v8.1, SCM, TMS, MFC v2.0, BackOffice v2.0) > OMS > Commerce > Delivery(Draft)**. 단 `[확정 정책]` 태그가 붙은 규칙은 문서 날짜와 무관하게 유지한다.

| 충돌 | 문서 A | 문서 B | 적용 |
|---|---|---|---|
| 상품 정본 | Commerce·WMS "WMS 소유" | WMS SEP-05 "발급 주체 미확정", CLAUDE.md `product-service` | `product-service` (ADR-0001) |
| MFC 재고 정본 | Commerce "WMS가 PPC 재고" | WMS v8.1·MFC·SCM·TMS "MFC 자체 소유" | `mfc-inventory-service` (ADR-0002) |
| 고객 배송 배차 | Commerce "TMS" | TMS·Delivery·OMS "Delivery" | `delivery-dispatch-service` (ADR-0007) |
| 권역→MFC 매핑 정본 | Commerce "TMS 승인 매핑" | BackOffice BO-07 "Delivery 실행 정본" | `delivery-serviceability-service` |
| 슬롯 정원 정본 | OMS "MFC Operations" | CLAUDE.md `delivery-capacity`+`mfc-capacity` | 고객 슬롯=`delivery-capacity`, 작업 여력=`mfc-capacity`, 둘 다 확보 (ADR-0008) |
| 첫 구매 무료배달 점유 | Commerce "OMS 직접" | CLAUDE.md `promotion-service` | 1차 `order-service`, 쿠폰 도입 시 `promotion-service`로 이관 검토 |
| 전달 자동완료 | Delivery "5시간 후 자동 완료" | OMS·Commerce "시간 경과 자동완료 없음" | 자동완료 없음 (확정 정책) |
| 주류 인증 | Delivery "라이더 신분증·서명" | OMS·Commerce `[확정 정책]` PG 인증 통합 | PG 인증 통합 |
| 분할 기준 | Delivery "10kg 이상" | OMS "10,000g 초과" | 10,000g 초과 (정확히 10kg은 미분할) |
| 재고 차감 시점 | Delivery "출고 시 차감 예시" | MFC IV-08 "실제 인계 시 H−q" | 인계 시 차감 |
| 배차 가능 시간 | Delivery "09:00~24:00" | OMS "00:00~08:00 배송 없음, 08/09시 개시 차이 미확정" | `[결정 필요]` 운영값 |
| Saga 방식 | CLAUDE.md "choreography 기본" | CLAUDE.md `order-orchestration-service` 존재, OMS A.1.15 사가 단계 추적 | 주문 사가는 orchestration, 전파는 choreography (ADR-0003) |
