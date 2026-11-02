package com.baropick.delivery.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Ids;
import com.baropick.delivery.domain.ArrivalException;
import com.baropick.delivery.domain.ArrivalExceptionRepository;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;

/**
 * 고객 도착 예외 (DECISIONS.md A-03).
 * - 라이더: 정상 판정이 불가능할 때(거리 초과·위치 불량) 사유와 함께 요청만 할 수 있다. 거리 제한을 스스로 해제할 수 없다.
 * - 관리자: 승인/반려. 승인 시 배송이 여전히 배송 중이고 요청 당시 라이더·배정 버전과 같아야 한다.
 * - 감사: 요청 당시 판정(거리·정확도·위치 경과·출처), 라이더 사유, 처리자·처리 사유·시각을 보존하고 상태 이력에도 남긴다.
 */
@Service
public class ArrivalExceptionService {

    public record View(String exceptionId, String deliveryId, String orderId, String riderId, int assignmentVersion,
                       ArrivalException.Reason reasonCode, String riderNote, String checkCode, Double distanceMeters,
                       Double accuracyMeters, Long locationAgeSeconds, String locationSource, Instant locationMeasuredAt,
                       ArrivalException.Status status, Instant requestedAt, String decidedBy, Instant decidedAt, String decisionNote,
                       String result) {}

    private final ArrivalExceptionRepository repo;
    private final DeliveryRepository deliveries;
    private final ArrivalPolicy arrival;
    private final HistoryRecorder history;
    private final Clock clock;

    public ArrivalExceptionService(ArrivalExceptionRepository repo, DeliveryRepository deliveries, ArrivalPolicy arrival,
                                   HistoryRecorder history, Clock clock) {
        this.repo = repo;
        this.deliveries = deliveries;
        this.arrival = arrival;
        this.history = history;
        this.clock = clock;
    }

    /** 라이더 예외 요청. 같은 배송에 처리 대기 요청이 있으면 그 요청을 그대로 돌려준다(멱등). */
    @Transactional
    public View request(String deliveryId, String riderId, ArrivalException.Reason reason, String note) {
        if (reason == null) throw ApiException.badRequest("REASON_REQUIRED", "예외 사유 코드가 필요합니다");
        String n = note == null ? null : note.strip();
        if (reason == ArrivalException.Reason.OTHER && (n == null || n.isEmpty())) {
            throw ApiException.badRequest("NOTE_REQUIRED", "기타 사유는 내용을 입력해야 합니다");
        }
        if (n != null && n.length() > 300) throw ApiException.badRequest("NOTE_TOO_LONG", "사유는 300자 이하");
        Delivery d = deliveries.findForUpdate(deliveryId).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", deliveryId));
        if (!riderId.equals(d.getRiderId())) throw ApiException.forbidden("NOT_ASSIGNED_RIDER", "이 배송에 배정된 라이더가 아닙니다");
        var pending = repo.findFirstByDeliveryIdAndStatus(deliveryId, ArrivalException.Status.PENDING);
        if (pending.isPresent()) return view(pending.get(), d, "ALREADY_PENDING");
        if (d.getStatus() != DeliveryStatus.IN_TRANSIT) {
            throw ApiException.conflict("INVALID_DELIVERY_STATUS", "배송 중(IN_TRANSIT)인 배송만 도착 예외를 요청할 수 있습니다 (현재 " + d.getStatus() + ")");
        }
        ArrivalPolicy.Check c = arrival.evaluate(d);
        if (c.allowed()) {
            throw ApiException.conflict("ARRIVAL_EXCEPTION_NOT_NEEDED", "현재 위치로 정상 도착 처리가 가능합니다. 고객 도착 버튼을 사용하세요");
        }
        if (c.code() == ArrivalPolicy.Code.POLICY_NOT_CONFIGURED) throw arrival.toException(c);
        ArrivalException e = repo.save(new ArrivalException(Ids.next("AEX"), deliveryId, riderId, d.getAssignmentVersion(), reason, n,
                c.code().name(), c.distanceMeters(), c.accuracyMeters(), c.locationAgeSeconds(), c.locationSource(), c.measuredAt(),
                clock.instant()));
        history.note(d, "RIDER:" + riderId, "고객 도착 예외 요청 " + e.getId() + " (사유 " + reason + ", 판정 " + c.code() + " — " + c.summary() + ")");
        return view(e, d, "REQUESTED");
    }

    /**
     * 관리자 승인 → ARRIVED_AT_DESTINATION. 처리 사유 필수. 이미 승인된 요청은 같은 결과(멱등).
     * 요청 이후 상태·배정이 바뀌었으면 요청을 CANCELLED 로 남기고 409 — 이 무효 기록은 롤백하지 않는다(noRollbackFor).
     * 상태 전이는 승인 기록보다 먼저 수행해, 전이 실패 시 승인 기록이 남지 않게 한다.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public View approve(String exceptionId, String adminId, String note) {
        String n = requireNote(note);
        ArrivalException e = repo.findById(exceptionId).orElseThrow(() -> ApiException.notFound("ARRIVAL_EXCEPTION_NOT_FOUND", exceptionId));
        Delivery d = deliveries.findForUpdate(e.getDeliveryId()).orElseThrow();
        if (e.getStatus() == ArrivalException.Status.APPROVED) return view(e, d, "ALREADY_APPROVED");
        if (e.getStatus() != ArrivalException.Status.PENDING) {
            throw ApiException.conflict("ARRIVAL_EXCEPTION_CLOSED", "이미 처리된 요청입니다 (" + e.getStatus() + ")");
        }
        boolean sameAssignment = e.getRiderId().equals(d.getRiderId()) && e.getAssignmentVersion() == d.getAssignmentVersion();
        if (d.getStatus() != DeliveryStatus.IN_TRANSIT || !sameAssignment) {
            e.decide(ArrivalException.Status.CANCELLED, adminId, clock.instant(),
                    "자동 무효: 요청 이후 배송 상태/배정 변경 (현재 " + d.getStatus() + ", 라이더 " + d.getRiderId() + ")");
            throw ApiException.conflict("ARRIVAL_EXCEPTION_OUTDATED", "요청 이후 배송 상태 또는 담당 라이더가 바뀌어 승인할 수 없습니다. 요청을 무효 처리했습니다");
        }
        Instant now = clock.instant();
        DeliveryStatus from = d.transition(DeliveryStatus.ARRIVED_AT_DESTINATION, now, DeliveryStatus.IN_TRANSIT);
        e.decide(ArrivalException.Status.APPROVED, adminId, now, n);
        history.record(d, from, "ADMIN:" + adminId, String.format("고객 도착 예외 승인 %s (라이더 사유 %s%s, 요청 시 판정 %s — 직선거리 %s / 정확도 %s / 측정 %s) 처리 사유: %s",
                e.getId(), e.getReasonCode(), e.getRiderNote() == null ? "" : " \"" + e.getRiderNote() + "\"", e.getCheckCode(),
                e.getDistanceM() == null ? "-" : String.format("%.1fm", e.getDistanceM()),
                e.getAccuracyM() == null ? "-" : String.format("%.1fm", e.getAccuracyM()),
                e.getLocationAgeS() == null ? "-" : e.getLocationAgeS() + "초 전", n));
        return view(e, d, "APPROVED");
    }

    @Transactional
    public View reject(String exceptionId, String adminId, String note) {
        String n = requireNote(note);
        ArrivalException e = repo.findById(exceptionId).orElseThrow(() -> ApiException.notFound("ARRIVAL_EXCEPTION_NOT_FOUND", exceptionId));
        Delivery d = deliveries.findForUpdate(e.getDeliveryId()).orElseThrow();
        if (e.getStatus() == ArrivalException.Status.REJECTED) return view(e, d, "ALREADY_REJECTED");
        if (e.getStatus() != ArrivalException.Status.PENDING) {
            throw ApiException.conflict("ARRIVAL_EXCEPTION_CLOSED", "이미 처리된 요청입니다 (" + e.getStatus() + ")");
        }
        e.decide(ArrivalException.Status.REJECTED, adminId, clock.instant(), n);
        history.note(d, "ADMIN:" + adminId, "고객 도착 예외 반려 " + e.getId() + " 처리 사유: " + n);
        return view(e, d, "REJECTED");
    }

    @Transactional(readOnly = true)
    public List<View> list(ArrivalException.Status status) {
        List<ArrivalException> rows = status == null ? repo.findTop100ByOrderByRequestedAtDesc() : repo.findByStatusOrderByRequestedAtAsc(status);
        return rows.stream().map(e -> view(e, deliveries.findById(e.getDeliveryId()).orElse(null), null)).toList();
    }

    @Transactional(readOnly = true)
    public View latestFor(String deliveryId) {
        return repo.findFirstByDeliveryIdOrderByRequestedAtDesc(deliveryId).map(e -> view(e, null, null)).orElse(null);
    }

    private static String requireNote(String note) {
        String n = note == null ? "" : note.strip();
        if (n.isEmpty()) throw ApiException.badRequest("NOTE_REQUIRED", "처리 사유를 입력해야 합니다");
        if (n.length() > 300) throw ApiException.badRequest("NOTE_TOO_LONG", "처리 사유는 300자 이하");
        return n;
    }

    private static View view(ArrivalException e, Delivery d, String result) {
        return new View(e.getId(), e.getDeliveryId(), d == null ? null : d.getOrderId(), e.getRiderId(), e.getAssignmentVersion(),
                e.getReasonCode(), e.getRiderNote(), e.getCheckCode(), e.getDistanceM(), e.getAccuracyM(), e.getLocationAgeS(),
                e.getLocationSource(), e.getLocationMeasuredAt(), e.getStatus(), e.getRequestedAt(), e.getDecidedBy(), e.getDecidedAt(),
                e.getDecisionNote(), result);
    }
}
