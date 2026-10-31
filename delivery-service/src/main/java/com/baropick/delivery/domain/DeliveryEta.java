package com.baropick.delivery.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "delivery_eta")
public class DeliveryEta {
    @Id private String deliveryId;
    @Enumerated(EnumType.STRING) private EtaStatus status;
    private String provider;
    private Integer etaSeconds;
    private Integer distanceMeters;
    private Instant computedAt;
    private Instant lastAttemptAt;
    private String lastError;

    protected DeliveryEta() {}

    public DeliveryEta(String deliveryId) { this.deliveryId = deliveryId; this.status = EtaStatus.UNAVAILABLE; }

    public void success(String provider, int etaSeconds, int distanceMeters, Instant now) {
        this.status = EtaStatus.OK; this.provider = provider; this.etaSeconds = etaSeconds; this.distanceMeters = distanceMeters;
        this.computedAt = now; this.lastAttemptAt = now; this.lastError = null;
    }

    /** 실패 시 기존 성공값(computedAt)은 보존하되 상태는 UNAVAILABLE — 가짜 정상 ETA 표시 금지. */
    public void failure(String error, Instant now) {
        this.status = EtaStatus.UNAVAILABLE; this.lastAttemptAt = now; this.lastError = error;
    }

    public void attempted(Instant now) { this.lastAttemptAt = now; }

    public String getDeliveryId() { return deliveryId; }
    public EtaStatus getStatus() { return status; }
    public String getProvider() { return provider; }
    public Integer getEtaSeconds() { return etaSeconds; }
    public Integer getDistanceMeters() { return distanceMeters; }
    public Instant getComputedAt() { return computedAt; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public String getLastError() { return lastError; }
}
