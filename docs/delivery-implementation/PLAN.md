# PLAN — 구현 순서·영향 범위·검증 계획

## 산출물 레이아웃

```
delivery-service/        Spring Boot 4.1 (Java 21) — Delivery 실제 구현 + testsupport(Mock OMS/MFC, 시뮬레이터)
simulator-admin/         Vite + React + TypeScript — SIMULATED_ROUTE 제어 + Mock OMS/MFC 조작
apps/customer_app/       Flutter Android 고객 앱 (com.baropick.delivery.customer)
apps/rider_app/          Flutter Android 라이더 앱 (com.baropick.delivery.rider)
docs/delivery-implementation/  BASELINE·PLAN·DECISIONS·CONTRACTS·TEST_MATRIX·SIMULATOR_RUNBOOK·FINAL_REPORT
scripts/                 PowerShell 실행·검증 스크립트
```

## 순서

| 단계 | 내용 | 검증 |
|---|---|---|
| P0 | BASELINE/DECISIONS/PLAN/CONTRACTS, Flutter 앱 생성(키 해시 전달) | 문서 |
| P1-a | 도메인 모델·Flyway 스키마·배송 생성(멱등)·배차 제안/수락/거절/만료/재배차 | D-01~03, D-17 |
| P1-b | Mock MFC 포장·QR·인계 확정, 인계 검증, 취소 경합 | D-04, D-05, D-12 |
| P1-c | 위치 수집 검증·최신 위치·STOMP·고객 조회 권한 | D-06~09, D-16 |
| P1-d | 완료 증빙·Outbox·Mock OMS inbox·분할 | D-13~15, D-18, D-19 |
| P1-e | 카카오 Local/Directions 어댑터, ETA 캐시·실패 처리 + **실 API 호출 검증** | D-11, D-31 |
| P1-f | 시뮬레이터 엔진(FIXTURE/KAKAO), 제어 API, 장애 주입, 프로필 격리 | D-20, D-25~33 |
| P1-g | React+TS 관리자 UI | 빌드 + API 연동 수동/스크립트 검증 |
| P2 | Flutter 고객·라이더 앱 | `flutter analyze/test/build apk` |
| P3 | 모드 A E2E (자동 시나리오 스크립트 + 실기기 [미검증 가능]) | D-21, D-34 |
| P4 | 모드 B 실기기 | 기기 연결 필요 → [미검증] 예상 |
