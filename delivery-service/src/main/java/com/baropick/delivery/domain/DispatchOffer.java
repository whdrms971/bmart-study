package com.baropick.delivery.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "dispatch_offer")
public class DispatchOffer {
    @Id private String id;
    private String attemptId;
    private String deliveryId;
    private String riderId;
    @Enumerated(EnumType.STRING) private OfferStatus status;
    private boolean viewed;
    private Instant offeredAt;
    private Instant expiresAt;
    private Instant respondedAt;
    private String reason;
    @Version private long version;

    protected DispatchOffer() {}

    public DispatchOffer(String id, String attemptId, String deliveryId, String riderId, Instant now, Instant expiresAt) {
        this.id = id; this.attemptId = attemptId; this.deliveryId = deliveryId; this.riderId = riderId;
        this.status = OfferStatus.OFFERED; this.offeredAt = now; this.expiresAt = expiresAt;
    }

    public void close(OfferStatus s, String reason, Instant now) { this.status = s; this.reason = reason; this.respondedAt = now; }
    public void markViewed() { this.viewed = true; }

    public String getId() { return id; }
    public String getAttemptId() { return attemptId; }
    public String getDeliveryId() { return deliveryId; }
    public String getRiderId() { return riderId; }
    public OfferStatus getStatus() { return status; }
    public boolean isViewed() { return viewed; }
    public Instant getOfferedAt() { return offeredAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRespondedAt() { return respondedAt; }
    public String getReason() { return reason; }
}
