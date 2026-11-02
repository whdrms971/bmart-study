package com.baropick.delivery.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 고객 도착 예외 요청과 관리자 처리 감사 기록. */
@Entity
@Table(name = "delivery_arrival_exception")
public class ArrivalException {

    public enum Reason { GPS_ERROR, ADDRESS_COORDINATE_ERROR, COMPLEX_ENTRY, OTHER }

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    @Id private String id;
    private String deliveryId;
    private String riderId;
    private int assignmentVersion;
    @Enumerated(EnumType.STRING) private Reason reasonCode;
    private String riderNote;
    private String checkCode;
    @Column(name = "distance_m") private Double distanceM;
    @Column(name = "accuracy_m") private Double accuracyM;
    @Column(name = "location_age_s") private Long locationAgeS;
    private String locationSource;
    private Instant locationMeasuredAt;
    @Enumerated(EnumType.STRING) private Status status;
    private Instant requestedAt;
    private String decidedBy;
    private Instant decidedAt;
    private String decisionNote;

    protected ArrivalException() {}

    public ArrivalException(String id, String deliveryId, String riderId, int assignmentVersion, Reason reasonCode, String riderNote,
                            String checkCode, Double distanceM, Double accuracyM, Long locationAgeS, String locationSource,
                            Instant locationMeasuredAt, Instant requestedAt) {
        this.id = id; this.deliveryId = deliveryId; this.riderId = riderId; this.assignmentVersion = assignmentVersion;
        this.reasonCode = reasonCode; this.riderNote = riderNote; this.checkCode = checkCode; this.distanceM = distanceM;
        this.accuracyM = accuracyM; this.locationAgeS = locationAgeS; this.locationSource = locationSource;
        this.locationMeasuredAt = locationMeasuredAt; this.status = Status.PENDING; this.requestedAt = requestedAt;
    }

    public void decide(Status s, String by, Instant at, String note) {
        this.status = s; this.decidedBy = by; this.decidedAt = at; this.decisionNote = note;
    }

    public String getId() { return id; }
    public String getDeliveryId() { return deliveryId; }
    public String getRiderId() { return riderId; }
    public int getAssignmentVersion() { return assignmentVersion; }
    public Reason getReasonCode() { return reasonCode; }
    public String getRiderNote() { return riderNote; }
    public String getCheckCode() { return checkCode; }
    public Double getDistanceM() { return distanceM; }
    public Double getAccuracyM() { return accuracyM; }
    public Long getLocationAgeS() { return locationAgeS; }
    public String getLocationSource() { return locationSource; }
    public Instant getLocationMeasuredAt() { return locationMeasuredAt; }
    public Status getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
}
