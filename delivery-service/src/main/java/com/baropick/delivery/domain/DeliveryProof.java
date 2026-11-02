package com.baropick.delivery.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "delivery_proof")
public class DeliveryProof {
    @Id private String id;
    private String deliveryId;
    private String riderId;
    private String requestId;
    @Enumerated(EnumType.STRING) private ProofMethod method;
    private String photoPath;
    private String photoSha256;
    private long photoBytes;
    private String note;
    @jakarta.persistence.Column(name = "distance_to_destination_m") private Double distanceToDestinationM;
    private Instant createdAt;
    /** P-09: 보관 기간이 지나 사진 파일을 삭제한 시각 (기록은 유지) */
    private Instant photoPurgedAt;

    protected DeliveryProof() {}

    public void markPhotoPurged(Instant at) { this.photoPurgedAt = at; }
    public Instant getPhotoPurgedAt() { return photoPurgedAt; }

    public DeliveryProof(String id, String deliveryId, String riderId, String requestId, ProofMethod method, String photoPath,
                         String photoSha256, long photoBytes, String note, Double distanceToDestinationM, Instant createdAt) {
        this.id = id; this.deliveryId = deliveryId; this.riderId = riderId; this.requestId = requestId; this.method = method;
        this.photoPath = photoPath; this.photoSha256 = photoSha256; this.photoBytes = photoBytes; this.note = note;
        this.distanceToDestinationM = distanceToDestinationM; this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getDeliveryId() { return deliveryId; }
    public String getRiderId() { return riderId; }
    public String getRequestId() { return requestId; }
    public ProofMethod getMethod() { return method; }
    public String getPhotoPath() { return photoPath; }
    public String getPhotoSha256() { return photoSha256; }
    public long getPhotoBytes() { return photoBytes; }
    public String getNote() { return note; }
    public Double getDistanceToDestinationM() { return distanceToDestinationM; }
    public Instant getCreatedAt() { return createdAt; }
}
