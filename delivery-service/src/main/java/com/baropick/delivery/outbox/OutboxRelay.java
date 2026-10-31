package com.baropick.delivery.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.baropick.delivery.auth.AuthKeys;
import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.config.SecurityConfig;
import com.baropick.delivery.domain.OutboxEvent;
import com.baropick.delivery.domain.OutboxEventRepository;

/**
 * Outbox → OMS 이벤트 전달 (at-least-once). OMS 는 (sourceSystem, eventId) 로 멱등 소비해야 한다.
 * 전송 성공 응답을 받기 전 장애가 나면 재전송되며, 중복 적용 방지는 수신 측 inbox 책임이다 (D-14).
 */
@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repo;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DeliveryProperties props;
    private final Environment env;
    private final AuthKeys keys;
    private final RestClient http;

    public OutboxRelay(OutboxEventRepository repo, TransactionTemplate tx, Clock clock, DeliveryProperties props, Environment env, AuthKeys keys) {
        this.repo = repo;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.env = env;
        this.keys = keys;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3));
        f.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    String endpoint() {
        String configured = props.outbox().omsEndpoint();
        if (configured != null && !configured.isBlank()) return configured;
        String port = env.getProperty("local.server.port");
        if (port == null || !env.matchesProfiles("local-test")) return null;
        return "http://127.0.0.1:" + port + "/mock-oms/internal/events";
    }

    @Scheduled(fixedDelayString = "${delivery.outbox.relay-interval-ms:2000}")
    public void relay() {
        String url = endpoint();
        if (url == null) return; // OMS 엔드포인트 미설정: 이벤트는 outbox 에 보존되고 전송되지 않음
        var batch = tx.execute(s -> repo.findTop20ByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAt(clock.instant()));
        if (batch == null) return;
        for (OutboxEvent e : batch) {
            publishOne(e.getEventId(), url);
        }
    }

    public void publishOne(String eventId, String url) {
        OutboxEvent e = repo.findById(eventId).orElse(null);
        if (e == null || e.getPublishedAt() != null) return;
        String error = null;
        try {
            http.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .header(SecurityConfig.INTERNAL_HEADER, keys.internalToken())
                    .body(e.getPayload()).retrieve().toBodilessEntity();
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        final String err = error;
        tx.executeWithoutResult(s -> repo.findById(eventId).ifPresent(ev -> {
            Instant now = clock.instant();
            if (err == null) {
                ev.published(now);
            } else {
                long backoff = Math.min(60, 1L << Math.min(5, ev.getAttempts()));
                ev.failed(err, now.plusSeconds(backoff));
            }
            repo.save(ev);
        }));
        if (err != null) log.warn("outbox 전송 실패 eventId={} type={} attempts={} error={}", eventId, e.getEventType(), e.getAttempts() + 1, err);
    }
}
