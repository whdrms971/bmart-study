package com.baropick.delivery.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 수신한 모든 위치 원본(거부 포함)을 판정 결과와 함께 남긴다. */
@Entity
@Table(name = "rider_location")
public class RiderLocation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String deliveryId;
    private String riderId;
    @Enumerated(EnumType.STRING) private LocationMode source;
    private String scenarioId;
    private long seq;
    private Double lat;
    private Double lng;
    @jakarta.persistence.Column(name = "accuracy_m") private Double accuracyM;
    private Double speedMps;
    private Double heading;
    private Instant measuredAt;
    private Instant receivedAt;
    @Enumerated(EnumType.STRING) private LocationVerdict verdict;
    private String reason;

    protected RiderLocation() {}

    public RiderLocation(String deliveryId, String riderId, LocationMode source, String scenarioId, long seq, Double lat, Double lng,
                         Double accuracyM, Double speedMps, Double heading, Instant measuredAt, Instant receivedAt,
                         LocationVerdict verdict, String reason) {
        this.deliveryId = deliveryId; this.riderId = riderId; this.source = source; this.scenarioId = scenarioId; this.seq = seq;
        this.lat = sanitize(lat); this.lng = sanitize(lng); this.accuracyM = sanitize(accuracyM); this.speedMps = sanitize(speedMps);
        this.heading = sanitize(heading); this.measuredAt = measuredAt; this.receivedAt = receivedAt; this.verdict = verdict;
        this.reason = reason;
    }

    /** NaN/Infinity 는 DB에 저장할 수 없으므로 null 로 기록 (판정 사유에 원인 기록). */
    private static Double sanitize(Double d) { return d == null || !Double.isFinite(d) ? null : d; }

    public Long getId() { return id; }
    public String getDeliveryId() { return deliveryId; }
    public String getRiderId() { return riderId; }
    public LocationMode getSource() { return source; }
    public String getScenarioId() { return scenarioId; }
    public long getSeq() { return seq; }
    public Double getLat() { return lat; }
    public Double getLng() { return lng; }
    public Double getAccuracyM() { return accuracyM; }
    public Instant getMeasuredAt() { return measuredAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public LocationVerdict getVerdict() { return verdict; }
    public String getReason() { return reason; }
}
