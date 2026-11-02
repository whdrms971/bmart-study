package com.baropick.delivery.tracking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.config.DeliveryProperties;

/**
 * 고객 주소 검색(카카오 Local, 서버 키) 사용자별 호출 제한 — DECISIONS.md SEC-03 (2026-11-02 결정: 분당 10회).
 * 최근 60초 이동 구간으로 센다. 거절된 호출은 세지 않는다(카카오를 호출하지 않았으므로).
 * 서버 메모리 기준이라 서버를 여러 대 띄우면 서버마다 따로 센다 — 다중 인스턴스 운영 시 공유 저장소(Redis 등)로 교체.
 */
@Component
public class PlaceSearchRateLimiter {
    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Clock clock;
    private final Integer perMinute;
    private final Map<String, Deque<Instant>> calls = new ConcurrentHashMap<>();

    public PlaceSearchRateLimiter(Clock clock, DeliveryProperties props) {
        this.clock = clock;
        this.perMinute = props.policy().placeSearchPerMinute();
    }

    /** 허용되면 호출을 기록하고 반환, 초과면 429 RATE_LIMITED (retryAfterSeconds 포함). */
    public void acquire(String userId) {
        int limit = DeliveryProperties.Policy.require(perMinute, "place-search-per-minute");
        Instant now = clock.instant();
        Instant from = now.minus(WINDOW);
        if (calls.size() > CLEANUP_THRESHOLD) {
            calls.entrySet().removeIf(e -> { synchronized (e.getValue()) { return e.getValue().isEmpty() || !e.getValue().peekLast().isAfter(from); } });
        }
        Deque<Instant> q = calls.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && !q.peekFirst().isAfter(from)) q.pollFirst();
            if (q.size() >= limit) {
                long retry = Math.max(1, Duration.between(now, q.peekFirst().plus(WINDOW)).toSeconds() + 1);
                throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "주소 검색은 1분에 " + limit + "회까지 가능합니다. " + retry + "초 후 다시 시도하세요",
                        Map.of("retryAfterSeconds", retry, "limitPerMinute", limit));
            }
            q.addLast(now);
        }
    }
}
