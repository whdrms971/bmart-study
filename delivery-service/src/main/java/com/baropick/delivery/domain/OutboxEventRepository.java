package com.baropick.delivery.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findTop20ByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAt(Instant now);
    List<OutboxEvent> findByAggregateIdOrderByCreatedAt(String aggregateId);
    List<OutboxEvent> findTop100ByOrderByCreatedAtDesc();
}
