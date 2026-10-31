package com.baropick.delivery.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
    @Id private String eventId;
    private String eventType;
    private String aggregateId;
    private String payload;
    private Instant createdAt;
    private Instant publishedAt;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;

    protected OutboxEvent() {}

    public OutboxEvent(String eventId, String eventType, String aggregateId, String payload, Instant now) {
        this.eventId = eventId; this.eventType = eventType; this.aggregateId = aggregateId; this.payload = payload;
        this.createdAt = now; this.nextAttemptAt = now;
    }

    public void published(Instant now) { this.publishedAt = now; this.attempts++; this.lastError = null; }

    public void failed(String error, Instant next) {
        this.attempts++; this.lastError = error == null ? null : error.substring(0, Math.min(300, error.length())); this.nextAttemptAt = next;
    }

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getAggregateId() { return aggregateId; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
}
