package com.baropick.delivery.tracking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Geo;
import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.config.DeliveryProperties.Policy;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.LatestLocation;
import com.baropick.delivery.domain.LatestLocationRepository;
import com.baropick.delivery.domain.LocationMode;
import com.baropick.delivery.domain.LocationVerdict;
import com.baropick.delivery.domain.RiderLocation;
import com.baropick.delivery.domain.RiderLocationRepository;

/**
 * 위치 수집 공통 경로. 실 GPS(라이더 앱 HTTP)와 SIMULATED_ROUTE(서버 내부 시뮬레이터 어댑터) 모두 이 서비스를 통과한다.
 * 출처(source)는 호출 경로가 결정하며 클라이언트 본문의 값을 신뢰하지 않는다.
 */
@Service
public class LocationIngestService {

    public record LocationPoint(Long seq, Double latitude, Double longitude, Double accuracyMeters, Double speedMps, Double heading,
                                Instant measuredAt) {}

    /**
     * @param referenceNow 미래/지연 판정 기준 시각. DEVICE_GPS=서버 실시각, SIMULATED_ROUTE=시나리오 가상 시각.
     * @param requiredAssignmentVersion 시뮬레이터가 시작 시 고정한 배정 버전(재배정 시 주입 권한 회수). 실 GPS 는 null.
     */
    public record IngestContext(String deliveryId, String riderId, LocationMode source, String scenarioId, Instant referenceNow,
                                Integer requiredAssignmentVersion) {}

    public record PointResult(long seq, LocationVerdict verdict, String reason) {}

    public record IngestResult(String deliveryId, int accepted, int total, List<PointResult> points) {}

    private final DeliveryRepository deliveries;
    private final RiderLocationRepository locations;
    private final LatestLocationRepository latestRepo;
    private final Policy policy;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public LocationIngestService(DeliveryRepository deliveries, RiderLocationRepository locations, LatestLocationRepository latestRepo,
                                 DeliveryProperties props, Clock clock, ApplicationEventPublisher events) {
        this.deliveries = deliveries;
        this.locations = locations;
        this.latestRepo = latestRepo;
        this.policy = props.policy();
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public IngestResult ingest(IngestContext ctx, List<LocationPoint> points) {
        int skew = Policy.require(policy.locationFutureSkewSeconds(), "location-future-skew-seconds");
        int maxDelay = Policy.require(policy.locationMaxDelaySeconds(), "location-max-delay-seconds");
        double maxSpeed = Policy.require(policy.locationMaxSpeedMps(), "location-max-speed-mps");
        if (points == null || points.isEmpty()) throw ApiException.badRequest("EMPTY_BATCH", "위치가 없습니다");
        if (points.size() > 100) throw ApiException.badRequest("BATCH_TOO_LARGE", "한 번에 최대 100개까지 전송할 수 있습니다");

        Delivery d = deliveries.findForUpdate(ctx.deliveryId()).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", ctx.deliveryId()));
        authorize(d, ctx);

        Instant received = clock.instant();
        Instant ref = ctx.referenceNow() == null ? received : ctx.referenceNow();
        LatestLocation latest = latestRepo.findById(d.getId()).orElse(null);
        List<PointResult> out = new ArrayList<>();
        int accepted = 0;
        boolean latestChanged = false;
        for (LocationPoint p : points) {
            long seq = p.seq() == null ? -1 : p.seq();
            LocationVerdict v;
            String reason = null;
            if (p.seq() == null || p.seq() < 0) {
                v = LocationVerdict.REJECTED_INVALID; reason = "seq 누락/음수";
            } else if (locations.existsByDeliveryIdAndSourceAndRiderIdAndSeq(d.getId(), ctx.source(), ctx.riderId(), seq)) {
                out.add(new PointResult(seq, LocationVerdict.DUPLICATE, "동일 seq 재수신"));
                continue; // 원본 1건만 보존
            } else if (!Geo.validLat(p.latitude()) || !Geo.validLng(p.longitude())) {
                v = LocationVerdict.REJECTED_INVALID; reason = "위도[-90,90]/경도[-180,180] 범위 또는 NaN";
            } else if (p.accuracyMeters() != null && (!Double.isFinite(p.accuracyMeters()) || p.accuracyMeters() < 0)) {
                v = LocationVerdict.REJECTED_INVALID; reason = "정확도 음수/NaN";
            } else if (p.measuredAt() == null) {
                v = LocationVerdict.REJECTED_INVALID; reason = "측정시각 누락";
            } else if (p.measuredAt().isAfter(ref.plusSeconds(skew))) {
                v = LocationVerdict.REJECTED_FUTURE; reason = "측정시각이 기준시각보다 " + skew + "초 이상 미래";
            } else if (latest != null && !p.measuredAt().isAfter(latest.getMeasuredAt())) {
                v = LocationVerdict.ACCEPTED_HISTORY; reason = "최신 위치보다 과거(역순/지연) → 최신 미반영";
            } else if (Duration.between(p.measuredAt(), ref).getSeconds() > maxDelay) {
                v = LocationVerdict.STALE; reason = "측정 후 " + maxDelay + "초 초과 지연 → 최신 미반영";
            } else if (latest != null && impliedSpeed(latest, p) > maxSpeed) {
                v = LocationVerdict.REJECTED_ANOMALY; reason = String.format("비정상 이동 속도 %.1fm/s > %.1f", impliedSpeed(latest, p), maxSpeed);
            } else {
                v = LocationVerdict.ACCEPTED_LATEST;
            }
            locations.save(new RiderLocation(d.getId(), ctx.riderId(), ctx.source(), ctx.scenarioId(), seq, p.latitude(), p.longitude(),
                    p.accuracyMeters(), p.speedMps(), p.heading(), p.measuredAt(), received, v, reason));
            if (v == LocationVerdict.ACCEPTED_LATEST) {
                if (latest == null) latest = new LatestLocation(d.getId());
                latest.update(ctx.riderId(), ctx.source(), ctx.scenarioId(), seq, p.latitude(), p.longitude(), p.accuracyMeters(),
                        p.measuredAt(), received);
                latestChanged = true;
                accepted++;
            } else if (v == LocationVerdict.ACCEPTED_HISTORY || v == LocationVerdict.STALE) {
                accepted++;
            }
            out.add(new PointResult(seq, v, reason));
        }
        if (latestChanged) {
            latestRepo.save(latest);
            events.publishEvent(DeliveryChanged.of(d.getId(), "LOCATION"));
        }
        return new IngestResult(d.getId(), accepted, points.size(), out);
    }

    private void authorize(Delivery d, IngestContext ctx) {
        if (!ctx.riderId().equals(d.getRiderId())) {
            throw ApiException.forbidden("NOT_ASSIGNED_RIDER", "이 배송에 배정된 라이더가 아니므로 위치를 보고할 수 없습니다");
        }
        if (!DeliveryStatus.RIDER_ACTIVE.contains(d.getStatus())) {
            throw ApiException.conflict("LOCATION_NOT_ACCEPTED_IN_STATE", "현재 배송 상태(" + d.getStatus() + ")에서는 위치를 받지 않습니다");
        }
        if (d.getLocationMode() != ctx.source()) {
            throw ApiException.conflict("LOCATION_MODE_MISMATCH",
                    "이 배송의 위치 모드는 서버가 " + d.getLocationMode() + "(으)로 고정했습니다");
        }
        if (ctx.requiredAssignmentVersion() != null && ctx.requiredAssignmentVersion() != d.getAssignmentVersion()) {
            throw ApiException.forbidden("ASSIGNMENT_CHANGED", "배정이 변경되어 기존 주입 권한이 회수되었습니다");
        }
    }

    private static double impliedSpeed(LatestLocation l, LocationPoint p) {
        double dt = Duration.between(l.getMeasuredAt(), p.measuredAt()).toMillis() / 1000.0;
        if (dt <= 0) return Double.POSITIVE_INFINITY;
        return Geo.distanceMeters(l.getLat(), l.getLng(), p.latitude(), p.longitude()) / dt;
    }
}
