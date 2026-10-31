package com.baropick.delivery.handover;

import java.time.Instant;

/**
 * Delivery → MFC 인계 판정 포트. MFC 가 포장 인계(재고 차감 포함)의 정본이다.
 * Delivery 는 MFC 의 확정 결과를 받아 라이더 인수 사실과 결합할 뿐, MFC 재고를 직접 쓰지 않는다.
 * 실제 MFC 서비스가 준비되면 이 포트의 구현만 교체한다.
 */
public interface MfcHandoverPort {

    /** executionId 는 결정적(배송·포장·배정버전)이므로 재전송해도 MFC 에서 중복 인계되지 않는다. */
    record HandoverCommand(String executionId, String deliveryId, String orderId, String mfcId, String packageId, String qrCode,
                           String riderId, int assignmentVersion) {}

    enum Outcome { HANDED_OVER, REJECTED, NOT_FOUND }

    record HandoverResult(Outcome outcome, String packageId, String handoverId, Instant handedOverAt, String code, String message) {}

    /** 인계 허가+확정. 타임아웃/연결 실패 시 {@link MfcUnavailableException} — 실패로 단정하지 않고 결과 조회로 해소한다. */
    HandoverResult authorizeAndCommit(HandoverCommand cmd);

    /** 원 실행 결과 조회. */
    HandoverResult query(String executionId);

    class MfcUnavailableException extends RuntimeException {
        public MfcUnavailableException(String msg, Throwable cause) { super(msg, cause); }
    }
}
