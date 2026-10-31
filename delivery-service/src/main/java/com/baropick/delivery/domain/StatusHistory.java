package com.baropick.delivery.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "delivery_status_history")
public class StatusHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String deliveryId;
    @Enumerated(EnumType.STRING) private DeliveryStatus fromStatus;
    @Enumerated(EnumType.STRING) private DeliveryStatus toStatus;
    private String actor;
    private String reason;
    private Instant at;

    protected StatusHistory() {}

    public StatusHistory(String deliveryId, DeliveryStatus from, DeliveryStatus to, String actor, String reason, Instant at) {
        this.deliveryId = deliveryId; this.fromStatus = from; this.toStatus = to; this.actor = actor; this.reason = reason; this.at = at;
    }

    public Long getId() { return id; }
    public String getDeliveryId() { return deliveryId; }
    public DeliveryStatus getFromStatus() { return fromStatus; }
    public DeliveryStatus getToStatus() { return toStatus; }
    public String getActor() { return actor; }
    public String getReason() { return reason; }
    public Instant getAt() { return at; }
}
