package com.baropick.delivery.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Geo;
import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.LatestLocation;
import com.baropick.delivery.domain.LatestLocationRepository;
import com.baropick.delivery.domain.LocationMode;

/**
 * 고객 도착 판정 (DECISIONS.md A-01~A-03).
 * 1) 담당 라이더의 최신 위치가 있어야 한다 — 없거나 다른 라이더의 위치면 재측정 요청
 * 2) 위치 측정 후 maxLocationAgeSeconds 이내 — 초과면 재측정 요청
 *    (실 GPS 는 단말 측정 시각, SIMULATED_ROUTE 는 가상 측정 시각 대신 서버 수신 시각 기준: 배속 재생의 가상 시계와 분리, S-09)
 * 3) 정확도 반경 maxAccuracyMeters 이하 — 정확도 없음/초과면 재측정 요청
 * 4) 배송지 좌표와 직선거리(대권 거리, Haversine) maxDistanceMeters 이하 — 초과면 거부
 * 거리는 0.01m 단위로 반올림해 비교한다(부동소수 오차로 150.0000001m 가 거부되지 않도록).
 */
@Component
public class ArrivalPolicy {

    public enum Code {
        OK, POLICY_NOT_CONFIGURED, LOCATION_MISSING, LOCATION_STALE, LOCATION_INACCURATE, TOO_FAR;

        public boolean remeasure() { return this == LOCATION_MISSING || this == LOCATION_STALE || this == LOCATION_INACCURATE; }
    }

    public record Check(boolean allowed, Code code, String message, Double distanceMeters, Double accuracyMeters,
                        Long locationAgeSeconds, String locationSource, Instant measuredAt,
                        Double maxDistanceMeters, Integer maxLocationAgeSeconds, Double maxAccuracyMeters) {

        public Map<String, Object> details() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", code.name());
            if (distanceMeters != null) m.put("distanceMeters", distanceMeters);
            if (accuracyMeters != null) m.put("accuracyMeters", accuracyMeters);
            if (locationAgeSeconds != null) m.put("locationAgeSeconds", locationAgeSeconds);
            if (locationSource != null) m.put("locationSource", locationSource);
            if (maxDistanceMeters != null) m.put("maxDistanceMeters", maxDistanceMeters);
            if (maxLocationAgeSeconds != null) m.put("maxLocationAgeSeconds", maxLocationAgeSeconds);
            if (maxAccuracyMeters != null) m.put("maxAccuracyMeters", maxAccuracyMeters);
            return m;
        }

        /** 이력·감사 기록용 한 줄 요약. */
        public String summary() {
            return String.format("직선거리 %s / 정확도 %s / 측정 %s / 출처 %s",
                    distanceMeters == null ? "-" : String.format("%.1fm", distanceMeters),
                    accuracyMeters == null ? "-" : String.format("%.1fm", accuracyMeters),
                    locationAgeSeconds == null ? "-" : locationAgeSeconds + "초 전",
                    locationSource == null ? "-" : locationSource);
        }
    }

    private final LatestLocationRepository latest;
    private final DeliveryProperties.Arrival cfg;
    private final Clock clock;

    public ArrivalPolicy(LatestLocationRepository latest, DeliveryProperties props, Clock clock) {
        this.latest = latest;
        this.cfg = props.arrival();
        this.clock = clock;
    }

    /** 판정만 수행(예외를 던지지 않음). 화면 표시와 도착 요청이 같은 판정을 쓴다. */
    public Check evaluate(Delivery d) {
        Double maxDist = cfg.maxDistanceMeters();
        Integer maxAge = cfg.maxLocationAgeSeconds();
        Double maxAcc = cfg.maxAccuracyMeters();
        if (maxDist == null || maxAge == null || maxAcc == null) {
            return new Check(false, Code.POLICY_NOT_CONFIGURED, "고객 도착 판정 정책값(delivery.arrival.*)이 설정되지 않아 도착 처리를 할 수 없습니다",
                    null, null, null, null, null, maxDist, maxAge, maxAcc);
        }
        LatestLocation l = latest.findById(d.getId()).orElse(null);
        if (l == null || d.getRiderId() == null || !d.getRiderId().equals(l.getRiderId())) {
            return new Check(false, Code.LOCATION_MISSING, "현재 위치가 확인되지 않습니다. 위치를 다시 측정한 뒤 시도하세요",
                    null, null, null, null, null, maxDist, maxAge, maxAcc);
        }
        double distance = Math.round(Geo.distanceMeters(l.getLat(), l.getLng(), d.getDestLat(), d.getDestLng()) * 100.0) / 100.0;
        Instant ref = l.getSource() == LocationMode.SIMULATED_ROUTE ? l.getReceivedAt() : l.getMeasuredAt();
        long age = ref == null ? Long.MAX_VALUE : Math.max(0, Duration.between(ref, clock.instant()).toSeconds());
        Double acc = l.getAccuracyM();
        String src = l.getSource() == null ? null : l.getSource().name();
        Long ageOut = age == Long.MAX_VALUE ? null : age;

        if (age > maxAge) {
            return new Check(false, Code.LOCATION_STALE, String.format("마지막 위치가 %s 전입니다(허용 %d초). 위치를 다시 측정한 뒤 시도하세요",
                    ageOut == null ? "오래" : ageOut + "초", maxAge), distance, acc, ageOut, src, l.getMeasuredAt(), maxDist, maxAge, maxAcc);
        }
        if (acc == null || !(acc <= maxAcc)) {
            return new Check(false, Code.LOCATION_INACCURATE, String.format("위치 정확도가 %s 입니다(허용 %.0fm 이하). 하늘이 보이는 곳에서 위치를 다시 측정하세요",
                    acc == null ? "확인 불가" : String.format("%.0fm", acc), maxAcc), distance, acc, ageOut, src, l.getMeasuredAt(), maxDist, maxAge, maxAcc);
        }
        if (distance > maxDist) {
            return new Check(false, Code.TOO_FAR, String.format("배송지에서 %.0fm 떨어져 있습니다. %.0fm 이내에서 도착 처리할 수 있습니다",
                    distance, maxDist), distance, acc, ageOut, src, l.getMeasuredAt(), maxDist, maxAge, maxAcc);
        }
        return new Check(true, Code.OK, String.format("배송지에서 %.0fm — 도착 처리 가능", distance), distance, acc, ageOut, src,
                l.getMeasuredAt(), maxDist, maxAge, maxAcc);
    }

    /** 허용되지 않은 판정을 API 오류로 변환. 거리 초과 409, 재측정 필요 422, 정책 미설정 503. */
    public ApiException toException(Check c) {
        return switch (c.code()) {
            case TOO_FAR -> ApiException.conflict("ARRIVAL_TOO_FAR", c.message()).withDetails(c.details());
            case LOCATION_MISSING, LOCATION_STALE, LOCATION_INACCURATE ->
                    ApiException.unprocessable("LOCATION_REMEASURE_REQUIRED", c.message()).withDetails(c.details());
            case POLICY_NOT_CONFIGURED -> ApiException.unavailable("POLICY_NOT_CONFIGURED", c.message());
            case OK -> throw new IllegalStateException("허용된 판정은 오류가 아닙니다");
        };
    }
}
