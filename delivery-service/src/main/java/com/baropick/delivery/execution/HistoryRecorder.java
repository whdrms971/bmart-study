package com.baropick.delivery.execution;

import java.time.Clock;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.StatusHistory;
import com.baropick.delivery.domain.StatusHistoryRepository;
import com.baropick.delivery.tracking.DeliveryChanged;

/** 상태 변경 이력(변경 전후·주체·사유·시각) 저장 + 커밋 후 실시간 전파 이벤트 발행. */
@Component
public class HistoryRecorder {
    private final StatusHistoryRepository repo;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public HistoryRecorder(StatusHistoryRepository repo, ApplicationEventPublisher events, Clock clock) {
        this.repo = repo;
        this.events = events;
        this.clock = clock;
    }

    public void record(Delivery d, DeliveryStatus from, String actor, String reason) {
        repo.save(new StatusHistory(d.getId(), from, d.getStatus(), actor, reason, clock.instant()));
        events.publishEvent(DeliveryChanged.of(d.getId(), "STATUS"));
    }

    public void note(Delivery d, String actor, String reason) {
        repo.save(new StatusHistory(d.getId(), d.getStatus(), d.getStatus(), actor, reason, clock.instant()));
        events.publishEvent(DeliveryChanged.of(d.getId(), "NOTE"));
    }
}
