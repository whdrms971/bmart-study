package com.baropick.delivery.domain;

public enum LocationVerdict {
    ACCEPTED_LATEST,     // 최신 위치로 반영
    ACCEPTED_HISTORY,    // 저장했으나 최신보다 과거(역순) → 최신 미반영
    STALE,               // 측정시각이 너무 오래됨 → 최신 미반영
    DUPLICATE,           // 동일 seq 재수신 → 무시
    REJECTED_INVALID,    // 범위/NaN/음수 정확도 등
    REJECTED_FUTURE,     // 측정시각이 허용 오차 이상 미래
    REJECTED_ANOMALY     // 비정상 이동 속도
}
