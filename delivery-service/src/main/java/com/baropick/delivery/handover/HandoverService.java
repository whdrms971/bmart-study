package com.baropick.delivery.handover;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryPackage;
import com.baropick.delivery.domain.DeliveryPackageRepository;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.PackageStatus;
import com.baropick.delivery.execution.HistoryRecorder;
import com.baropick.delivery.handover.MfcHandoverPort.HandoverCommand;
import com.baropick.delivery.handover.MfcHandoverPort.HandoverResult;
import com.baropick.delivery.handover.MfcHandoverPort.MfcUnavailableException;
import com.baropick.delivery.handover.MfcHandoverPort.Outcome;
import com.baropick.delivery.intake.InternalDeliveryController.PackagesReadyEvent;
import com.baropick.delivery.outbox.OutboxService;

/**
 * 포장 인계. 포장 준비(MFC 통지) ≠ MFC 인계 확정 ≠ 라이더 인수 — 포장별로 분리 기록한다.
 * 배송의 모든 유효 포장이 HANDED_OVER 일 때만 PICKED_UP (D-04). 응답 불명은 실패로 단정하지 않는다.
 */
@Service
public class HandoverService {
    public static final String QR_PREFIX = "BPKG";

    private final DeliveryRepository deliveries;
    private final DeliveryPackageRepository packages;
    private final MfcHandoverPort mfc;
    private final HistoryRecorder history;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public HandoverService(DeliveryRepository deliveries, DeliveryPackageRepository packages, MfcHandoverPort mfc, HistoryRecorder history,
                           OutboxService outbox, JdbcTemplate jdbc, Clock clock, org.springframework.transaction.support.TransactionTemplate tx) {
        this.tx = tx;
        this.deliveries = deliveries;
        this.packages = packages;
        this.mfc = mfc;
        this.history = history;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record PackagesReadyResult(String deliveryId, String result, int packageCount) {}

    @Transactional
    public PackagesReadyResult onPackagesReady(PackagesReadyEvent ev) {
        try {
            jdbc.update("insert into inbox_event(source_system, event_id, event_type, applied_at) values (?,?,?,?)",
                    ev.sourceSystem(), ev.eventId(), "PackagesReady", Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException dup) {
            return new PackagesReadyResult(ev.deliveryId(), "DUPLICATE_EVENT_IGNORED", ev.packageIds().size());
        }
        Delivery d = deliveries.findForUpdate(ev.deliveryId()).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", ev.deliveryId()));
        if (d.getStatus() == DeliveryStatus.CANCELLED) return new PackagesReadyResult(d.getId(), "DELIVERY_CANCELLED", 0);
        if (new HashSet<>(ev.packageIds()).size() != ev.packageIds().size()) throw ApiException.badRequest("DUPLICATE_PACKAGE_ID", "중복 포장 ID");
        if (d.isPackagesReady()) {
            var existing = packages.findByDeliveryIdOrderByPackageId(d.getId()).stream().map(DeliveryPackage::getPackageId).toList();
            if (new HashSet<>(existing).equals(new HashSet<>(ev.packageIds()))) return new PackagesReadyResult(d.getId(), "ALREADY_READY", existing.size());
            throw ApiException.conflict("REPACK_NOT_SUPPORTED", "포장 구성 변경(재포장)은 아직 지원하지 않습니다 [결정 필요]");
        }
        Instant now = clock.instant();
        for (String pid : ev.packageIds()) {
            if (packages.existsById(pid)) throw ApiException.conflict("PACKAGE_ID_CONFLICT", "다른 배송에 이미 존재하는 포장 ID: " + pid);
            packages.save(new DeliveryPackage(pid, d.getId(), now));
        }
        d.markPackagesReady(now);
        history.note(d, ev.sourceSystem(), "MFC 포장 준비 완료 통지 (포장 " + ev.packageIds().size() + "개, 예정 " + d.getExpectedPackageCount() + "개)");
        return new PackagesReadyResult(d.getId(), "READY", ev.packageIds().size());
    }

    public record ScanResult(String deliveryId, String packageId, String result, int handedOver, int total, DeliveryStatus deliveryStatus,
                             String message) {}

    /** QR 형식: BPKG|{packageId}|{token}. token 검증은 MFC 가 한다. */
    public static String packageIdFromQr(String qr) {
        if (qr == null) return null;
        String[] parts = qr.trim().split("\\|");
        if (parts.length != 3 || !QR_PREFIX.equals(parts[0]) || parts[1].isBlank()) return null;
        return parts[1];
    }

    /** 트랜잭션 단계 결과: 응답(result) 또는 커밋 후 던질 오류(error) 또는 다음 단계로 넘길 MFC 호출 준비(prep). */
    private record Step(ScanResult result, ApiException error, Prep prep) {
        static Step done(ScanResult r) { return new Step(r, null, null); }
        static Step fail(ApiException e) { return new Step(null, e, null); }
    }

    private record Prep(HandoverCommand cmd, boolean resolveUnknownFirst) {}

    /**
     * 포장 인계 (QR 스캔). 외부 MFC 호출을 DB 트랜잭션 밖에서 한다.
     * 1) 짧은 트랜잭션: 배송 잠금·검증 후 포장을 "확인 중"(HANDOVER_UNKNOWN)으로 표시 → 이 사이 취소 요청은 회수 경로로 거절된다(기존 규칙)
     * 2) 트랜잭션 없이 MFC 인계 확정 요청 (응답 불명이면 다음 스캔에서 원결과 조회)
     * 3) 짧은 트랜잭션: 결과 반영, 전 포장 인계면 PICKED_UP
     * 이전 구현은 배송 행 잠금과 DB 연결을 쥔 채 MFC 를 호출해, MFC 가 느리거나 동시 스캔이 연결 풀 크기를 넘으면
     * 연결이 고갈되어 서버 전체가 멈췄다 (2026-11-02 부하 테스트: 동시 30건 스캔 p50 10초·시간 초과).
     * 검증 실패 시에도 남긴 이력(비지정 라이더 시도 등)은 커밋한 뒤 오류를 돌려준다.
     */
    public ScanResult scan(String deliveryId, String riderId, String qr) {
        String packageId = packageIdFromQr(qr);
        if (packageId == null) throw ApiException.badRequest("QR_UNREADABLE", "인계 QR 형식이 아닙니다");

        Step s1 = tx.execute(st -> prepare(deliveryId, riderId, packageId, qr));
        if (s1.error() != null) throw s1.error();
        if (s1.result() != null) return s1.result();

        Prep prep = s1.prep();
        HandoverResult r = null;
        try {
            if (prep.resolveUnknownFirst()) {
                r = mfc.query(prep.cmd().executionId()); // 불명 해소: 원 실행 결과 먼저 조회
                if (r.outcome() == Outcome.NOT_FOUND) r = mfc.authorizeAndCommit(prep.cmd());
            } else {
                r = mfc.authorizeAndCommit(prep.cmd());
            }
        } catch (MfcUnavailableException e) {
            r = null;
        }
        final HandoverResult outcome = r;
        Step s3 = tx.execute(st -> apply(prep, outcome));
        if (s3.error() != null) throw s3.error();
        return s3.result();
    }

    private Step prepare(String deliveryId, String riderId, String packageId, String qr) {
        Delivery d = deliveries.findForUpdate(deliveryId).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", deliveryId));
        if (!riderId.equals(d.getRiderId())) {
            history.note(d, "RIDER:" + riderId, "비지정 라이더 인계 시도 차단 (포장 " + packageId + ")");
            return Step.fail(ApiException.forbidden("NOT_ASSIGNED_RIDER", "이 배송에 배정된 라이더가 아닙니다"));
        }
        if (d.getStatus() != DeliveryStatus.ARRIVED_AT_MFC) {
            return Step.fail(ApiException.conflict("INVALID_STATE", "MFC 도착 후에만 인계할 수 있습니다 (현재 " + d.getStatus() + ")"));
        }
        if (!d.isPackagesReady()) return Step.fail(ApiException.conflict("PACKAGES_NOT_READY", "MFC 포장 준비가 완료되지 않았습니다"));
        DeliveryPackage p = packages.findById(packageId).orElse(null);
        if (p == null || !p.getDeliveryId().equals(deliveryId)) {
            return Step.fail(ApiException.conflict("PACKAGE_NOT_IN_DELIVERY", "이 배송의 포장이 아닙니다 (다른 주문 QR)"));
        }
        if (p.getStatus() == PackageStatus.HANDED_OVER) return Step.done(result(d, packageId, "ALREADY_HANDED_OVER", "이미 인계된 포장입니다"));
        if (p.getStatus() == PackageStatus.VOIDED) return Step.fail(ApiException.conflict("PACKAGE_VOIDED", "무효(취소/재포장)된 포장입니다"));
        boolean wasUnknown = p.getStatus() == PackageStatus.HANDOVER_UNKNOWN;
        String executionId = deliveryId + ":" + packageId + ":v" + d.getAssignmentVersion();
        p.unknown("MFC 인계 처리 중", clock.instant()); // 진행 중 표시: 동시 취소는 회수 경로로 거절, 재스캔은 원결과 조회
        return new Step(null, null, new Prep(cmd(d, packageId, qr, riderId, executionId), wasUnknown));
    }

    private Step apply(Prep prep, HandoverResult r) {
        HandoverCommand c = prep.cmd();
        Delivery d = deliveries.findForUpdate(c.deliveryId()).orElseThrow();
        DeliveryPackage p = packages.findById(c.packageId()).orElseThrow();
        Instant now = clock.instant();
        if (p.getStatus() == PackageStatus.HANDED_OVER) {
            return Step.done(result(d, c.packageId(), "ALREADY_HANDED_OVER", "이미 인계된 포장입니다")); // 동시 재스캔이 먼저 반영
        }
        if (d.getStatus() == DeliveryStatus.CANCELLED || p.getStatus() == PackageStatus.VOIDED) {
            // MFC 출고 중지 후 취소가 먼저 반영됨. MFC 가 중지한 포장은 인계될 수 없다(S3 fence)
            if (r != null && r.outcome() == Outcome.HANDED_OVER) {
                history.note(d, "MFC", "취소된 배송에 인계 확정 응답 (포장 " + c.packageId() + ", handover " + r.handoverId()
                        + ") — 계약 위반 의심, 회수 확인 필요");
            }
            return Step.fail(ApiException.conflict("DELIVERY_CANCELLED", "주문이 취소되어 인계할 수 없습니다. 포장을 MFC 에 반납하세요"));
        }
        if (r == null) {
            p.unknown("MFC 응답 불명", now);
            history.note(d, "RIDER:" + c.riderId(), "인계 결과 불명 (포장 " + c.packageId() + ") — 재스캔 시 원결과 조회");
            return Step.done(result(d, c.packageId(), "HANDOVER_RESULT_UNKNOWN", "MFC 인계 확인 중입니다. 잠시 후 다시 스캔하세요"));
        }
        if (r.outcome() != Outcome.HANDED_OVER) {
            p.backToReady(r.code(), now);
            history.note(d, "MFC", "인계 거절 (포장 " + c.packageId() + "): " + r.code());
            return Step.fail(ApiException.conflict(r.code() == null ? "MFC_REJECTED" : r.code(),
                    r.message() == null ? "MFC가 인계를 거절했습니다" : r.message()));
        }
        // MFC 가 인계를 확정했으면 물건은 라이더에게 있다 → 기록은 항상 남긴다
        p.handedOver(r.handoverId(), c.riderId(), r.handedOverAt() == null ? now : r.handedOverAt(), now);
        packages.flush();
        List<DeliveryPackage> all = packages.findByDeliveryIdOrderByPackageId(d.getId());
        boolean allDone = all.stream().allMatch(x -> x.getStatus() == PackageStatus.HANDED_OVER);
        boolean sameAssignment = c.riderId().equals(d.getRiderId()) && c.assignmentVersion() == d.getAssignmentVersion();
        if (allDone && d.getStatus() == DeliveryStatus.ARRIVED_AT_MFC && sameAssignment) {
            DeliveryStatus from = d.transition(DeliveryStatus.PICKED_UP, now, DeliveryStatus.ARRIVED_AT_MFC);
            d.markPickedUp(now);
            history.record(d, from, "RIDER:" + c.riderId(), "전 포장(" + all.size() + "개) MFC 인계 확정·라이더 인수");
            outbox.append("DeliveryPickedUp", d.getId(), Map.of("deliveryId", d.getId(), "orderId", d.getOrderId(), "riderId", c.riderId(),
                    "pickedUpAt", now.toString(), "packageIds", all.stream().map(DeliveryPackage::getPackageId).toList()));
        } else if (!sameAssignment || d.getStatus() != DeliveryStatus.ARRIVED_AT_MFC) {
            history.note(d, "MFC", "인계 확정 반영(포장 " + c.packageId() + ") — 처리 중 배송 상태/배정이 바뀜(현재 " + d.getStatus() + "), 상태 전이 보류·확인 필요");
        } else {
            history.note(d, "RIDER:" + c.riderId(), "포장 인계 확정 " + c.packageId());
        }
        return Step.done(result(d, c.packageId(), "HANDED_OVER", allDone ? "모든 포장 인계 완료" : "인계 완료, 남은 포장을 스캔하세요"));
    }

    private HandoverCommand cmd(Delivery d, String packageId, String qr, String riderId, String executionId) {
        return new HandoverCommand(executionId, d.getId(), d.getOrderId(), d.getMfcId(), packageId, qr, riderId, d.getAssignmentVersion());
    }

    private ScanResult result(Delivery d, String packageId, String result, String msg) {
        List<DeliveryPackage> all = packages.findByDeliveryIdOrderByPackageId(d.getId());
        int done = (int) all.stream().filter(x -> x.getStatus() == PackageStatus.HANDED_OVER).count();
        return new ScanResult(d.getId(), packageId, result, done, all.size(), d.getStatus(), msg);
    }
}
