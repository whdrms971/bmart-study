package com.baropick.delivery.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baropick.delivery.common.Ids;
import com.baropick.delivery.domain.OutboxEvent;
import com.baropick.delivery.domain.OutboxEventRepository;

import tools.jackson.databind.json.JsonMapper;

/** Transactional Outbox: 상태 변경과 같은 트랜잭션에 이벤트를 기록한다. 발행은 OutboxRelay 가 담당. */
@Service
public class OutboxService {
    public static final String SOURCE_SYSTEM = "DELIVERY";

    private final OutboxEventRepository repo;
    private final JsonMapper json;
    private final Clock clock;

    public OutboxService(OutboxEventRepository repo, JsonMapper json, Clock clock) {
        this.repo = repo;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent append(String eventType, String aggregateId, Map<String, Object> body) {
        Instant now = clock.instant();
        String eventId = Ids.next("EVT");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", 1);
        envelope.put("sourceSystem", SOURCE_SYSTEM);
        envelope.put("occurredAt", now.toString());
        envelope.putAll(body);
        return repo.save(new OutboxEvent(eventId, eventType, aggregateId, json.writeValueAsString(envelope), now));
    }
}
