package com.baropick.delivery.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "delivery_latest_location")
public class LatestLocation {
    @Id private String deliveryId;
    private String riderId;
    @Enumerated(EnumType.STRING) private LocationMode source;
    private String scenarioId;
    private long seq;
    private double lat;
    private double lng;
    @jakarta.persistence.Column(name = "accuracy_m") private Double accuracyM;
    private Instant measuredAt;
    private Instant receivedAt;

    protected LatestLocation() {}

    public LatestLocation(String deliveryId) { this.deliveryId = deliveryId; }

    public void update(String riderId, LocationMode source, String scenarioId, long seq, double lat, double lng, Double accuracyM,
                       Instant measuredAt, Instant receivedAt) {
        this.riderId = riderId; this.source = source; this.scenarioId = scenarioId; this.seq = seq; this.lat = lat; this.lng = lng;
        this.accuracyM = accuracyM; this.measuredAt = measuredAt; this.receivedAt = receivedAt;
    }

    public String getDeliveryId() { return deliveryId; }
    public String getRiderId() { return riderId; }
    public LocationMode getSource() { return source; }
    public String getScenarioId() { return scenarioId; }
    public long getSeq() { return seq; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public Double getAccuracyM() { return accuracyM; }
    public Instant getMeasuredAt() { return measuredAt; }
    public Instant getReceivedAt() { return receivedAt; }
}
