package com.baropick.delivery.dispatch;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Geo;
import com.baropick.delivery.common.Ids;
import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.config.DeliveryProperties.Policy;
import com.baropick.delivery.config.DeliveryProperties.RadiusPolicy;
import com.baropick.delivery.domain.AttemptStatus;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.DispatchAttempt;
import com.baropick.delivery.domain.DispatchAttemptRepository;
import com.baropick.delivery.domain.DispatchOffer;
import com.baropick.delivery.domain.DispatchOfferRepository;
import com.baropick.delivery.domain.DutyStatus;
import com.baropick.delivery.domain.OfferStatus;
import com.baropick.delivery.domain.Rider;
import com.baropick.delivery.domain.RiderRepository;
import com.baropick.delivery.execution.HistoryRecorder;
import com.baropick.delivery.mfc.MfcRegistry;
import com.baropick.delivery.tracking.OfferChanged;

/**
 * 배차: 시도(attempt) 단위로 적격 라이더 전원에게 제안(offer)하고, 먼저 수락한 1명만 배정한다.
 * 동시 수락은 배송 행 비관적 잠금으로 직렬화되어 정확히 1명만 성공한다 (D-02).
 * 자동 추천 모드라도 라이더 수락 없이 배정하지 않는다.
 */
@Service
public class DispatchService {

    private final DeliveryRepository deliveries;
    private final DispatchAttemptRepository attempts;
    private final DispatchOfferRepository offers;
    private final RiderRepository riders;
    private final MfcRegistry mfcs;
    private final Policy policy;
    private final HistoryRecorder history;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DispatchService(DeliveryRepository deliveries, DispatchAttemptRepository attempts, DispatchOfferRepository offers,
                           RiderRepository riders, MfcRegistry mfcs, DeliveryProperties props, HistoryRecorder history,
                           ApplicationEventPublisher events, Clock clock) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.offers = offers;
        this.riders = riders;
        this.mfcs = mfcs;
        this.policy = props.policy();
        this.history = history;
        this.events = events;
        this.clock = clock;
    }

    public record DispatchOutcome(String deliveryId, String result, int offersCreated, String note) {}

    /** REQUESTED 배송에 대해 새 배차 시도를 만든다. 적격 라이더가 없으면 상태를 유지하고 사유만 기록한다. */
    @Transactional
    public DispatchOutcome tryDispatch(String deliveryId) {
        Delivery d = deliveries.findForUpdate(deliveryId).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", deliveryId));
        if (d.getStatus() != DeliveryStatus.REQUESTED || d.isDispatchStalled()) {
            return new DispatchOutcome(deliveryId, "SKIPPED", 0, "status=" + d.getStatus() + ", stalled=" + d.isDispatchStalled());
        }
        int maxAttempts = Policy.require(policy.maxDispatchAttempts(), "max-dispatch-attempts");
        int ttl = Policy.require(policy.offerTtlSeconds(), "offer-ttl-seconds");
        int lastNo = attempts.findFirstByDeliveryIdOrderByAttemptNoDesc(deliveryId).map(DispatchAttempt::getAttemptNo).orElse(0);
        if (lastNo >= maxAttempts) {
            Instant now = clock.instant();
            d.stall("DISPATCH_ATTEMPTS_EXHAUSTED: 최대 " + maxAttempts + "회 시도 후 미배정 — 관리자 확인 필요", now);
            history.note(d, "SYSTEM", "배차 시도 소진, 관리자 재배차 필요");
            return new DispatchOutcome(deliveryId, "STALLED", 0, d.getDispatchNote());
        }
        List<Rider> eligible = eligibleRiders(d);
        if (eligible.isEmpty()) {
            d.setDispatchNote("NO_ELIGIBLE_RIDER: 운행 중·신규배차 허용·반경/용량 조건을 만족하는 라이더 없음");
            return new DispatchOutcome(deliveryId, "NO_ELIGIBLE_RIDER", 0, d.getDispatchNote());
        }
        Instant now = clock.instant();
        DispatchAttempt attempt = attempts.save(new DispatchAttempt(Ids.next("ATT"), deliveryId, lastNo + 1, now));
        for (Rider r : eligible) {
            DispatchOffer o = offers.save(new DispatchOffer(Ids.next("OFR"), attempt.getId(), deliveryId, r.getId(), now, now.plusSeconds(ttl)));
            events.publishEvent(new OfferChanged(r.getId(), o.getId(), deliveryId, OfferStatus.OFFERED.name()));
        }
        d.setDispatchNote(null);
        DeliveryStatus from = d.transition(DeliveryStatus.OFFERING, now, DeliveryStatus.REQUESTED);
        history.record(d, from, "SYSTEM", "배차 시도 #" + attempt.getAttemptNo() + " 제안 " + eligible.size() + "건");
        return new DispatchOutcome(deliveryId, "OFFERED", eligible.size(), null);
    }

    List<Rider> eligibleRiders(Delivery d) {
        var site = mfcs.require(d.getMfcId());
        int maxConcurrent = Policy.require(policy.maxConcurrentDeliveries(), "max-concurrent-deliveries");
        Instant now = clock.instant();
        Set<String> rejected = new HashSet<>();
        for (DispatchOffer o : offers.findByDeliveryIdOrderByOfferedAt(d.getId())) {
            // 거절한 라이더, 그리고 수락 후 배정 해제(배차취소/재배정)된 라이더는 같은 배송에 다시 제안하지 않는다
            if (o.getStatus() == OfferStatus.REJECTED || o.getStatus() == OfferStatus.ACCEPTED) rejected.add(o.getRiderId());
        }
        List<Rider> out = new ArrayList<>();
        for (Rider r : riders.findByDutyStatus(DutyStatus.ON_DUTY_ACCEPTING)) {
            if (rejected.contains(r.getId())) continue;
            if (deliveries.countByRiderIdAndStatusIn(r.getId(), DeliveryStatus.RIDER_ACTIVE) >= maxConcurrent) continue;
            if (site.radiusPolicy() == RadiusPolicy.ENFORCE) {
                int fresh = Policy.require(policy.riderLocationFreshnessSeconds(), "rider-location-freshness-seconds");
                if (r.getLastLat() == null || r.getLastLocationAt() == null) continue;
                if (r.getLastLocationAt().isBefore(now.minusSeconds(fresh))) continue;
                double dist = Geo.distanceMeters(r.getLastLat(), r.getLastLng(), site.latitude(), site.longitude());
                if (dist > site.radiusMeters()) continue;
            }
            out.add(r);
        }
        return out;
    }

    public record AcceptResult(String offerId, String deliveryId, String result, int assignmentVersion) {}

    @Transactional
    public AcceptResult accept(String offerId, String riderId) {
        DispatchOffer peek = offers.findById(offerId).orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", offerId));
        if (!peek.getRiderId().equals(riderId)) throw ApiException.forbidden("NOT_YOUR_OFFER", "본인에게 온 제안이 아닙니다");
        Delivery d = deliveries.findForUpdate(peek.getDeliveryId()).orElseThrow();
        DispatchOffer o = offers.findById(offerId).orElseThrow();
        Instant now = clock.instant();
        if (d.getStatus() == DeliveryStatus.CANCELLED) throw ApiException.conflict("DELIVERY_CANCELLED", "취소된 배송입니다");
        if (o.getStatus() == OfferStatus.ACCEPTED) return new AcceptResult(offerId, d.getId(), "ALREADY_ACCEPTED", d.getAssignmentVersion());
        if (o.getStatus() == OfferStatus.SUPERSEDED || (d.getRiderId() != null && !d.getRiderId().equals(riderId))) {
            throw ApiException.conflict("ALREADY_ASSIGNED", "이미 다른 라이더에게 배정되었습니다");
        }
        if (o.getStatus() == OfferStatus.EXPIRED || !now.isBefore(o.getExpiresAt())) {
            if (o.getStatus() == OfferStatus.OFFERED) {
                o.close(OfferStatus.EXPIRED, "응답 시간 초과(수락 시점 판정)", now);
                closeAttemptIfDone(d, o.getAttemptId(), now);
            }
            throw ApiException.conflict("OFFER_EXPIRED", "응답 시간이 만료된 제안입니다");
        }
        if (o.getStatus() != OfferStatus.OFFERED || d.getStatus() != DeliveryStatus.OFFERING) {
            throw ApiException.conflict("OFFER_NOT_ACTIVE", "유효하지 않은 제안입니다 (" + o.getStatus() + ")");
        }
        int maxConcurrent = Policy.require(policy.maxConcurrentDeliveries(), "max-concurrent-deliveries");
        if (deliveries.countByRiderIdAndStatusIn(riderId, DeliveryStatus.RIDER_ACTIVE) >= maxConcurrent) {
            throw ApiException.conflict("CAPACITY_EXCEEDED", "동시 수행 가능 건수를 초과했습니다");
        }
        o.close(OfferStatus.ACCEPTED, "수락", now);
        for (DispatchOffer other : offers.findByAttemptId(o.getAttemptId())) {
            if (!other.getId().equals(offerId) && other.getStatus() == OfferStatus.OFFERED) {
                other.close(OfferStatus.SUPERSEDED, "다른 라이더 배정 완료", now);
                events.publishEvent(new OfferChanged(other.getRiderId(), other.getId(), d.getId(), OfferStatus.SUPERSEDED.name()));
            }
        }
        attempts.findById(o.getAttemptId()).ifPresent(a -> a.close(AttemptStatus.ASSIGNED, riderId, now));
        d.assignRider(riderId, now);
        DeliveryStatus from = d.transition(DeliveryStatus.ASSIGNED, now, DeliveryStatus.OFFERING);
        history.record(d, from, "RIDER:" + riderId, "배차 수락 (offer " + offerId + ")");
        events.publishEvent(new OfferChanged(riderId, offerId, d.getId(), OfferStatus.ACCEPTED.name()));
        return new AcceptResult(offerId, d.getId(), "ASSIGNED", d.getAssignmentVersion());
    }

    @Transactional
    public void reject(String offerId, String riderId, String reason) {
        DispatchOffer peek = offers.findById(offerId).orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", offerId));
        if (!peek.getRiderId().equals(riderId)) throw ApiException.forbidden("NOT_YOUR_OFFER", "본인에게 온 제안이 아닙니다");
        Delivery d = deliveries.findForUpdate(peek.getDeliveryId()).orElseThrow();
        DispatchOffer o = offers.findById(offerId).orElseThrow();
        if (o.getStatus() != OfferStatus.OFFERED) throw ApiException.conflict("OFFER_NOT_ACTIVE", "이미 종료된 제안입니다 (" + o.getStatus() + ")");
        Instant now = clock.instant();
        o.close(OfferStatus.REJECTED, reason == null || reason.isBlank() ? "사유 미입력" : reason, now);
        events.publishEvent(new OfferChanged(riderId, offerId, d.getId(), OfferStatus.REJECTED.name()));
        closeAttemptIfDone(d, o.getAttemptId(), now);
    }

    @Transactional
    public void markViewed(String offerId, String riderId) {
        DispatchOffer o = offers.findById(offerId).orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", offerId));
        if (!o.getRiderId().equals(riderId)) throw ApiException.forbidden("NOT_YOUR_OFFER", "본인에게 온 제안이 아닙니다");
        o.markViewed();
    }

    /** 응답 시간 경과 제안 만료 처리. 시도 내 활성 제안이 없으면 다음 시도를 위해 REQUESTED 로 되돌린다. */
    @Transactional
    public int expireDueOffers() {
        Instant now = clock.instant();
        int n = 0;
        Set<String> touched = new HashSet<>();
        for (DispatchOffer peek : offers.findByStatusAndExpiresAtBefore(OfferStatus.OFFERED, now)) touched.add(peek.getDeliveryId());
        for (String deliveryId : touched) {
            Delivery d = deliveries.findForUpdate(deliveryId).orElseThrow();
            for (DispatchOffer o : offers.findByDeliveryIdOrderByOfferedAt(deliveryId)) {
                if (o.getStatus() == OfferStatus.OFFERED && !now.isBefore(o.getExpiresAt())) {
                    o.close(OfferStatus.EXPIRED, o.isViewed() ? "상세 확인 후 미응답 만료" : "미확인 만료", now);
                    events.publishEvent(new OfferChanged(o.getRiderId(), o.getId(), deliveryId, OfferStatus.EXPIRED.name()));
                    closeAttemptIfDone(d, o.getAttemptId(), now);
                    n++;
                }
            }
        }
        return n;
    }

    private void closeAttemptIfDone(Delivery d, String attemptId, Instant now) {
        boolean anyActive = offers.findByAttemptId(attemptId).stream().anyMatch(x -> x.getStatus() == OfferStatus.OFFERED);
        if (anyActive || d.getStatus() != DeliveryStatus.OFFERING) return;
        attempts.findById(attemptId).ifPresent(a -> a.close(AttemptStatus.EXHAUSTED, "제안 전원 거절/만료", now));
        DeliveryStatus from = d.transition(DeliveryStatus.REQUESTED, now, DeliveryStatus.OFFERING);
        history.record(d, from, "SYSTEM", "배차 시도 종료(전원 거절/만료) → 재배차 대기");
    }

    /** 배송 취소 등으로 열린 제안을 모두 닫는다. 호출자가 배송 행 잠금을 보유해야 한다. */
    public void cancelOpenOffers(Delivery d, String reason, Instant now) {
        for (DispatchOffer o : offers.findByDeliveryIdOrderByOfferedAt(d.getId())) {
            if (o.getStatus() == OfferStatus.OFFERED) {
                o.close(OfferStatus.CANCELLED, reason, now);
                events.publishEvent(new OfferChanged(o.getRiderId(), o.getId(), d.getId(), OfferStatus.CANCELLED.name()));
            }
        }
        for (DispatchAttempt a : attempts.findByDeliveryIdOrderByAttemptNo(d.getId())) {
            if (a.getStatus() == AttemptStatus.OPEN) a.close(AttemptStatus.CANCELLED, reason, now);
        }
    }

    /** 관리자: 배차 소진(stalled) 배송을 다시 배차 대상으로 돌린다. 기존 시도 이력은 유지. */
    @Transactional
    public void resumeDispatch(String deliveryId, String actor) {
        Delivery d = deliveries.findForUpdate(deliveryId).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", deliveryId));
        if (d.getStatus() != DeliveryStatus.REQUESTED) throw ApiException.conflict("INVALID_STATE", "REQUESTED 상태만 재배차할 수 있습니다");
        d.clearStall(clock.instant());
        history.note(d, actor, "관리자 재배차 재개");
    }
}
