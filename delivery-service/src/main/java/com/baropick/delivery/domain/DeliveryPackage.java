package com.baropick.delivery.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "delivery_package")
public class DeliveryPackage {
    @Id private String packageId;
    private String deliveryId;
    @Enumerated(EnumType.STRING) private PackageStatus status;
    private String handoverId;
    private String handedOverRiderId;
    private Instant handedOverAt;
    private String lastError;
    private Instant updatedAt;

    protected DeliveryPackage() {}

    public DeliveryPackage(String packageId, String deliveryId, Instant now) {
        this.packageId = packageId; this.deliveryId = deliveryId; this.status = PackageStatus.READY; this.updatedAt = now;
    }

    public void handedOver(String handoverId, String riderId, Instant at, Instant now) {
        this.status = PackageStatus.HANDED_OVER; this.handoverId = handoverId; this.handedOverRiderId = riderId;
        this.handedOverAt = at; this.lastError = null; this.updatedAt = now;
    }

    public void unknown(String err, Instant now) { this.status = PackageStatus.HANDOVER_UNKNOWN; this.lastError = err; this.updatedAt = now; }
    public void backToReady(String err, Instant now) { this.status = PackageStatus.READY; this.lastError = err; this.updatedAt = now; }
    public void voided(Instant now) { this.status = PackageStatus.VOIDED; this.updatedAt = now; }

    public String getPackageId() { return packageId; }
    public String getDeliveryId() { return deliveryId; }
    public PackageStatus getStatus() { return status; }
    public String getHandoverId() { return handoverId; }
    public String getHandedOverRiderId() { return handedOverRiderId; }
    public Instant getHandedOverAt() { return handedOverAt; }
    public String getLastError() { return lastError; }
}
