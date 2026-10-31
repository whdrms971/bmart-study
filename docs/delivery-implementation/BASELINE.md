# BASELINE — 2026-10-31 착수 시점 진단

## 1. 작업공간 실측 [코드 확인]

| 항목 | 결과 |
|---|---|
| 경로 | `C:\development\Delivery` |
| Git | 저장소 아님 → 로컬 `git init`만 수행 (원격 없음, push 안 함) |
| 기존 파일 | `CLAUDE.md`, `business.zip`, `docs/business/**` (zip과 동일 15개 파일, 이미 해제되어 있었음) |
| 기존 코드 | **없음** (backend·Flutter·웹·OpenAPI·마이그레이션·테스트 모두 부재) |
| `docs/architecture/`, `docs/adr/` | **없음**. 문서에서 참조하는 `service-map.md`, `events.md`, ADR-0001~0008은 실제로 존재하지 않음 → 내용 미확인 |

## 2. 도구 실측 [코드 확인]

| 도구 | 버전 |
|---|---|
| JDK | Oracle 21.0.11 (JAVA_HOME), Flutter용 JDK 17.0.12 |
| Gradle | 시스템 설치 없음 → Spring Initializr 래퍼 Gradle 9.8.2 |
| Spring Boot | 4.1.2 (Initializr 기본값; 3.x는 더 이상 생성 불가) — Spring 7, Hibernate 7.4, Jackson 3.1, Flyway 12 |
| Flutter / Dart | 3.47.2 / 3.13.2, Android SDK 37 (일부 라이선스 미승인 경고) |
| Node / npm | 24.18.1 / 11.16.0 |
| Docker | CLI만 존재, 데몬 미기동 → PostgreSQL 실검증 불가, 로컬은 H2(PostgreSQL 모드) 사용 |
| adb | `platform-tools\adb.exe` 존재, **연결 기기 0대** |
| `KAKAO_REST_API_KEY` | 환경변수 존재(값은 확인·출력하지 않음) |

## 3. 문서 기준선 [문서 확인]

읽은 문서: `overview.md`, `domain-map.md`, `glossary.md`, `Delivery-business-logic.md`(Draft), `flows/cancel-vs-handover.md`, `OMS-business-logic.md`(인계·취소·완료 관련 절), `MFC-business-logic.md`(IV-08, WK-08, `AuthorizePackageHandover`/`CommitPackageHandover`, PKG/SHP 상태).

| 구분 | 내용 |
|---|---|
| 현재 구현됨 | 없음 |
| 문서에만 있음 | Delivery 상태 흐름(가게 도착→픽업 완료→고객 도착→전달 완료), 배차 시도/제안 번호, QR 전수 인계, 사진 증빙 완료, MFC PKG `OPEN→SEALED→HANDED_OVER`, SHP `PREPARING→READY→PARTIALLY_HANDED_OVER→HANDED_OVER`, 인계 시 재고 차감, 멱등 키 규칙 |
| 미구현(이번 범위) | Delivery 백엔드 전체, Mock OMS/MFC, 위치 시뮬레이터, 관리자 UI, 고객/라이더 앱 |
| 충돌·미확정 | `DECISIONS.md` 참조 |

## 4. 서비스 경계 판단 [설계 제안]

도메인 맵은 `delivery-dispatch / delivery-execution / delivery-tracking / delivery-capacity / delivery-serviceability` 서비스명을 언급하지만 실제 코드·ADR이 없다. CLAUDE.md §3.1 지침대로 **단일 Spring Boot `delivery-service` 안에 책임별 패키지**(`dispatch`, `execution`, `handover`, `tracking`, `eta`, `outbox`)로 분리하고, 추후 분리 가능하도록 패키지 간 의존을 서비스 인터페이스로 제한한다.
Mock OMS/MFC·시뮬레이터는 `testsupport` 패키지에 두고 `local-test` 프로필에서만 빈이 생성된다. Delivery와는 HTTP 계약(`/internal/**`)과 포트 인터페이스(`MfcHandoverPort`, outbox relay URL)로만 통신한다.
