package com.baropick.delivery.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Delivery 실행 상태 (Delivery 정본). MFC 포장 준비·MFC 인계 확정·라이더 인수는 포장(package) 단위로 별도 관리하며
 * PICKED_UP 은 "해당 배송의 모든 유효 포장이 MFC 인계 확정 + 라이더 인수"된 경우에만 된다.
 */
public enum DeliveryStatus {
    REQUESTED,              // 배송 작업 생성, 배차 대기
    OFFERING,               // 배차 제안 진행 중
    ASSIGNED,               // 라이더 수락·배정 확정
    ARRIVED_AT_MFC,         // 라이더 MFC 도착 (인계 진행 가능)
    PICKED_UP,              // 전 포장 인계 확정
    IN_TRANSIT,             // 라이더가 배송 출발
    ARRIVED_AT_DESTINATION, // 라이더가 고객 도착 표시
    DELIVERED,              // 증빙 검증 후 완료 확정 (자동 전이 없음)
    CANCELLED;

    public static final Set<DeliveryStatus> TERMINAL = EnumSet.of(DELIVERED, CANCELLED);
    public static final Set<DeliveryStatus> RIDER_ACTIVE = EnumSet.of(ASSIGNED, ARRIVED_AT_MFC, PICKED_UP, IN_TRANSIT, ARRIVED_AT_DESTINATION);
    public static final Set<DeliveryStatus> BEFORE_PICKUP = EnumSet.of(REQUESTED, OFFERING, ASSIGNED, ARRIVED_AT_MFC);

    public boolean isTerminal() { return TERMINAL.contains(this); }

    public boolean atOrAfter(DeliveryStatus other) {
        if (this == CANCELLED || other == CANCELLED) return this == other;
        return this.ordinal() >= other.ordinal();
    }
}
