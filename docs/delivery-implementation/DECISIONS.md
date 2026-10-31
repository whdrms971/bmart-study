# DECISIONS — 정책 충돌·미확정·구현상 선택

태그: `[문서 확인]` `[설계 제안]` `[결정 필요]`

## 1. 문서 충돌 (임의 해결 금지 → 확정 정책 기준 적용)

| ID | 충돌 | 적용 | 근거 |
|---|---|---|---|
| C-01 | Delivery 초안 "결제완료 5시간 후 자동 전달 완료" ↔ OMS·도메인맵 "시간 경과 자동완료 없음" | **자동완료 미구현.** 어떤 스케줄러도 `DELIVERED`로 전이하지 않음. 누락은 관리자 조회(완료 지연 목록)로만 노출 | domain-map §3, CLAUDE.md §2.2 [문서 확인] |
| C-02 | 분할 "10kg 이상" ↔ "10,000g 초과" | `totalWeightGrams > 10000`일 때만 분할. 10,000g은 미분할 (Mock OMS 구현, D-18 테스트) | overview §4 [문서 확인] |
| C-03 | 고객 배송 주체 "TMS"(Commerce/MFC 원문) ↔ Delivery | 고객 배송=Delivery. 간선 TMS 참조는 변경하지 않음 | domain-map §3 |
| C-04 | 재고 차감 "출고 시" ↔ "인계 시" | Delivery는 재고를 쓰지 않음. Mock MFC가 인계 확정 시점에만 차감 사실을 기록 | MFC IV-08 |
| C-05 | 주류 "라이더 신분증·서명" ↔ PG 인증 통합 | 라이더 앱에 신분증 확인 기능 없음 | overview §4 |

## 2. 결정 필요 — 운영값 (기본값을 0/무제한으로 두지 않음)

아래 값은 운영 프로필에서 **미설정 시 해당 기능이 기동 실패/차단**된다. `local-test` 프로필에만 **TEST-ONLY** 값이 `application-local-test.yml`에 명시된다.

| ID | 항목 | local-test 값 (확정 아님) | 운영 미설정 시 |
|---|---|---|---|
| P-01 | 배차 제안 응답 제한시간 | 60초 (문서 참고값과 동일, 미확정) | 배차 차단 |
| P-02 | 배차 반경 | 근거리 MFC 3,000m / 원거리 가상 MFC `TEST_BYPASS` | 배차 차단 |
| P-03 | 재시도 횟수·간격 | 3회, 즉시 | 재시도 없음 + 관리자 노출 |
| P-04 | 배차 가능 시간(09~24 vs 08시) | 검사 안 함(TEST) | `[결정 필요]` 상태로 표시 |
| P-05 | 고객에게 라이더 위치 공개 시작 시점 | `PICKED_UP` 이후 | 공개 안 함 |
| P-06 | 위치 측정시각 허용 미래 오차 / 최대 지연 | +30s / 120s(초과 시 저장하되 최신으로 쓰지 않음) | 위치 수신 차단 |
| P-07 | 비정상 이동 속도 임계 | 50 m/s(180km/h) | 위치 수신 차단 |
| P-08 | ETA 재계산 최소 간격 | 60초 | ETA 비활성("확인 불가") |
| P-09 | 위치 이력·증빙 사진 보관 기간 | 로컬 파일/DB 무기한(테스트) | `[결정 필요]` |
| P-10 | 라이더 동시 수행 최대 건수 | 3 (문서 참고값) | 1 |
| P-11 | 픽업 후 라이더 배차취소 | 불가(지원센터 처리 필요 → 409) | 동일 |
| P-12 | 분할배송 세부(포장 분배 규칙) | Mock OMS: 10,000g 초과 시 배송 2건, 무게 절반씩 | `[결정 필요]` |
| P-13 | 부분 인계 규칙 | 전량 인계 전 배송 시작 불가. 부분 인계 후 취소는 거절(회수 경로) | `[결정 필요]` |
| P-14 | 배차 알림 전달 기술 (SSE/FCM/폴링) | STOMP WebSocket + 앱 폴링 백업. FCM 미사용 | `[결정 필요]` |

## 3. 구현 선택 [설계 제안]

| ID | 선택 | 이유 |
|---|---|---|
| S-01 | 단일 Spring Boot 4.1 서비스 + 책임별 패키지 | 기존 서비스 경계 코드 없음 (CLAUDE.md §3.1) |
| S-02 | H2(PostgreSQL 모드) 로컬·테스트, Flyway 마이그레이션은 PostgreSQL 호환 SQL | Docker 미기동. `postgres` 프로필 제공하나 **[미검증]** |
| S-03 | 상태 전이: 배송 행 비관적 락(SELECT … FOR UPDATE) + `@Version` | 동시 수락·인계/취소 경합 단일 결과 |
| S-04 | Transactional Outbox + 스케줄 relay(HTTP POST) + Mock OMS inbox `(sourceSystem,eventId)` | D-14 |
| S-05 | 인증: HS256 JWT(Spring Resource Server). local-test는 테스트 계정 로그인 엔드포인트 제공 | 기존 Auth 서비스 없음. 운영 IdP 연동 시 교체 |
| S-06 | 실시간: STOMP over WebSocket(`/ws`), CONNECT 시 JWT 검증, SUBSCRIBE 시 주문 소유권 검사 | D-06 |
| S-07 | 위치 모드는 **MFC/테스트 시나리오 설정으로 서버가 배송 생성 시 고정**. 앱이 보내는 source 값은 신뢰하지 않음 | CLAUDE.md §3.2 |
| S-08 | 카카오 지도 Flutter: `kakao_map_sdk` 1.3.1 (verified publisher yhs.kr, BSD-3, Android 네이티브 `com.kakao.vectormap` 래퍼). **서드파티** 패키지임 | pub.dev 확인. 카카오 Maps SDK 자체 약관은 카카오 콘솔 기준 |
| S-09 | 시뮬레이터 시간: 결정적 가상 시계. 시뮬레이션 좌표의 `measuredAt`=가상 시각, 서버 `receivedAt`=실시각 별도 저장 | 배속 시 미래 시각 오판 방지 |
| S-10 | 시뮬레이션 위치도 `LocationIngestService`(실 GPS와 동일)를 통과. 주입은 서버 내부 어댑터만 가능, HTTP로 `source=SIMULATED`를 받는 경로 없음 | CLAUDE.md §A-1② |
| S-11 | FIXTURE 경로는 **직접 작성한 좌표열**(도로 정확성 보장 안 함). 카카오 응답은 저장·재사용하지 않고 메모리에만 보관 | 약관상 캐싱 조건 미확인 |
