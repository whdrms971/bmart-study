package com.baropick.delivery.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dispatch_attempt")
public class DispatchAttempt {
    @Id private String id;
    private String deliveryId;
    private int attemptNo;
    @Enumerated(EnumType.STRING) private AttemptStatus status;
    private String reason;
    private Instant createdAt;
    private Instant closedAt;

    protected DispatchAttempt() {}

    public DispatchAttempt(String id, String deliveryId, int attemptNo, Instant now) {
        this.id = id; this.deliveryId = deliveryId; this.attemptNo = attemptNo; this.status = AttemptStatus.OPEN; this.createdAt = now;
    }

    public void close(AttemptStatus s, String reason, Instant now) { this.status = s; this.reason = reason; this.closedAt = now; }

    public String getId() { return id; }
    public String getDeliveryId() { return deliveryId; }
    public int getAttemptNo() { return attemptNo; }
    public AttemptStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
}
