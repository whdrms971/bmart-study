package com.baropick.delivery.domain;

import java.time.Instant;

import com.baropick.delivery.common.ApiException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "delivery")
public class Delivery {
    @Id private String id;
    private String orderId;
    private String customerId;
    private String mfcId;
    private String sourceSystem;
    private String requestId;
    private String requestHash;
    @Enumerated(EnumType.STRING) private DeliveryStatus status;
    @Enumerated(EnumType.STRING) private LocationMode locationMode;
    private String riderId;
    private int assignmentVersion;
    private double destLat;
    private double destLng;
    private String destAddress;
    private String destAddressDetail;
    private double originLat;
    private double originLng;
    private Integer totalWeightGrams;
    private int expectedPackageCount;
    private boolean packagesReady;
    private String customerNote;
    private String dispatchNote;
    private boolean dispatchStalled;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant pickedUpAt;
    private Instant deliveredAt;
    private Instant cancelledAt;
    @Version @Column(name = "version") private long version;

    protected Delivery() {}

    public Delivery(String id, String orderId, String customerId, String mfcId, String sourceSystem, String requestId,
                    String requestHash, LocationMode mode, double destLat, double destLng, String destAddress,
                    String destAddressDetail, double originLat, double originLng, Integer totalWeightGrams,
                    int expectedPackageCount, String customerNote, Instant now) {
        this.id = id; this.orderId = orderId; this.customerId = customerId; this.mfcId = mfcId;
        this.sourceSystem = sourceSystem; this.requestId = requestId; this.requestHash = requestHash;
        this.locationMode = mode; this.destLat = destLat; this.destLng = destLng; this.destAddress = destAddress;
        this.destAddressDetail = destAddressDetail; this.originLat = originLat; this.originLng = originLng;
        this.totalWeightGrams = totalWeightGrams; this.expectedPackageCount = expectedPackageCount;
        this.customerNote = customerNote; this.status = DeliveryStatus.REQUESTED; this.createdAt = now; this.updatedAt = now;
    }

    /** 상태 전이. 허용되지 않는 출발 상태면 409 INVALID_STATE. 이전 상태를 반환. */
    public DeliveryStatus transition(DeliveryStatus to, Instant now, DeliveryStatus... allowedFrom) {
        boolean ok = false;
        for (DeliveryStatus s : allowedFrom) {
            if (s == status) { ok = true; break; }
        }
        if (!ok) {
            throw ApiException.conflict("INVALID_STATE",
                    "현재 배송 상태(" + status + ")에서는 " + to + "(으)로 변경할 수 없습니다");
        }
        DeliveryStatus from = status;
        status = to;
        updatedAt = now;
        return from;
    }

    public void assignRider(String riderId, Instant now) {
        this.riderId = riderId; this.assignmentVersion++; this.updatedAt = now; this.dispatchStalled = false; this.dispatchNote = null;
    }

    public void unassignRider(Instant now) {
        this.riderId = null; this.assignmentVersion++; this.updatedAt = now;
    }

    public void markPackagesReady(Instant now) { this.packagesReady = true; this.updatedAt = now; }
    public void markPickedUp(Instant now) { this.pickedUpAt = now; }
    public void markDelivered(Instant now) { this.deliveredAt = now; }
    public void markCancelled(Instant now) { this.cancelledAt = now; }
    public void stall(String note, Instant now) { this.dispatchStalled = true; this.dispatchNote = note; this.updatedAt = now; }
    public void clearStall(Instant now) { this.dispatchStalled = false; this.dispatchNote = null; this.updatedAt = now; }
    public void setDispatchNote(String note) { this.dispatchNote = note; }

    public String getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public String getMfcId() { return mfcId; }
    public String getSourceSystem() { return sourceSystem; }
    public String getRequestId() { return requestId; }
    public String getRequestHash() { return requestHash; }
    public DeliveryStatus getStatus() { return status; }
    public LocationMode getLocationMode() { return locationMode; }
    public String getRiderId() { return riderId; }
    public int getAssignmentVersion() { return assignmentVersion; }
    public double getDestLat() { return destLat; }
    public double getDestLng() { return destLng; }
    public String getDestAddress() { return destAddress; }
    public String getDestAddressDetail() { return destAddressDetail; }
    public double getOriginLat() { return originLat; }
    public double getOriginLng() { return originLng; }
    public Integer getTotalWeightGrams() { return totalWeightGrams; }
    public int getExpectedPackageCount() { return expectedPackageCount; }
    public boolean isPackagesReady() { return packagesReady; }
    public String getCustomerNote() { return customerNote; }
    public String getDispatchNote() { return dispatchNote; }
    public boolean isDispatchStalled() { return dispatchStalled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPickedUpAt() { return pickedUpAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public long getVersion() { return version; }
}
