package com.baropick.delivery.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "rider")
public class Rider {
    @Id private String id;
    private String displayName;
    @Enumerated(EnumType.STRING) private DutyStatus dutyStatus;
    private Double lastLat;
    private Double lastLng;
    private Instant lastLocationAt;
    private Instant lastSeenAt;
    @Version private long version;

    protected Rider() {}

    public Rider(String id, String displayName) { this.id = id; this.displayName = displayName; this.dutyStatus = DutyStatus.OFF_DUTY; }

    public void setDuty(DutyStatus s, Instant now) { this.dutyStatus = s; this.lastSeenAt = now; }

    public void updateIdleLocation(double lat, double lng, Instant measuredAt, Instant now) {
        this.lastLat = lat; this.lastLng = lng; this.lastLocationAt = measuredAt; this.lastSeenAt = now;
    }

    public void seen(Instant now) { this.lastSeenAt = now; }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public DutyStatus getDutyStatus() { return dutyStatus; }
    public Double getLastLat() { return lastLat; }
    public Double getLastLng() { return lastLng; }
    public Instant getLastLocationAt() { return lastLocationAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
