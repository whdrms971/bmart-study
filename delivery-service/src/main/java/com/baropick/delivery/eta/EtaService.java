package com.baropick.delivery.eta;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryEta;
import com.baropick.delivery.domain.DeliveryEtaRepository;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.LatestLocation;
import com.baropick.delivery.domain.LatestLocationRepository;
import com.baropick.delivery.kakao.KakaoClient;
import com.baropick.delivery.tracking.DeliveryChanged;

import jakarta.annotation.PreDestroy;

/**
 * 고객 배송지까지 ETA. 카카오모빌리티 Directions 로 계산하며 매 좌표마다 호출하지 않는다(최소 간격 정책 P-08).
 * 실패 시 가짜 ETA 를 만들지 않고 UNAVAILABLE + 마지막 성공 시각을 유지한다 (D-11).
 */
@Service
public class EtaService {
    private static final Logger log = LoggerFactory.getLogger(EtaService.class);
    static final Set<DeliveryStatus> ETA_STATES = Set.of(DeliveryStatus.IN_TRANSIT, DeliveryStatus.ARRIVED_AT_DESTINATION);
    public static final String PROVIDER = "KAKAO_DIRECTIONS";
    public static final String PROVIDER_NEAR = "NEAR_DESTINATION";
    /** 직선거리 이 값 미만이면 경로 API 를 부르지 않는다 [설계 제안, 운영값 결정 필요]. */
    static final double NEAR_DESTINATION_METERS = 30;

    private final DeliveryRepository deliveries;
    private final LatestLocationRepository latest;
    private final DeliveryEtaRepository etas;
    private final KakaoClient kakao;
    private final DeliveryProperties.Policy policy;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "eta-worker");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public EtaService(DeliveryRepository deliveries, LatestLocationRepository latest, DeliveryEtaRepository etas, KakaoClient kakao,
                      DeliveryProperties props, TransactionTemplate tx, ApplicationEventPublisher events, Clock clock) {
        this.deliveries = deliveries;
        this.latest = latest;
        this.etas = etas;
        this.kakao = kakao;
        this.policy = props.policy();
        this.tx = tx;
        this.events = events;
        this.clock = clock;
    }

    /** 위치 반영 후 호출. 간격 정책을 만족하면 비동기 재계산. */
    public void maybeRecompute(String deliveryId) {
        Integer minInterval = policy.etaMinIntervalSeconds();
        if (minInterval == null) return; // 정책 미설정: ETA 비활성 (UNAVAILABLE 표시)
        Delivery d = deliveries.findById(deliveryId).orElse(null);
        if (d == null || !ETA_STATES.contains(d.getStatus())) return;
        DeliveryEta e = etas.findById(deliveryId).orElse(null);
        Instant now = clock.instant();
        if (e != null && e.getLastAttemptAt() != null && e.getLastAttemptAt().plusSeconds(minInterval).isAfter(now)) return;
        if (!inFlight.add(deliveryId)) return;
        executor.submit(() -> {
            try {
                recomputeNow(deliveryId);
            } finally {
                inFlight.remove(deliveryId);
            }
        });
    }

    /** 동기 재계산 (테스트·관리자 강제 갱신용). */
    public DeliveryEta recomputeNow(String deliveryId) {
        Delivery d = deliveries.findById(deliveryId).orElse(null);
        LatestLocation l = latest.findById(deliveryId).orElse(null);
        if (d == null || l == null) return null;
        Instant now = clock.instant();
        double straight = com.baropick.delivery.common.Geo.distanceMeters(l.getLat(), l.getLng(), d.getDestLat(), d.getDestLng());
        if (straight < NEAR_DESTINATION_METERS) {
            // 카카오 길찾기는 출발·도착 5m 이내면 실패(result_code 104) — 호출하지 않고 "도착 지점 근처"로 기록
            DeliveryEta near = tx.execute(s -> {
                DeliveryEta e = etas.findById(deliveryId).orElseGet(() -> new DeliveryEta(deliveryId));
                e.success(PROVIDER_NEAR, 0, (int) Math.round(straight), now);
                return etas.save(e);
            });
            events.publishEvent(DeliveryChanged.of(deliveryId, "ETA"));
            return near;
        }
        KakaoClient.DirectionsResult r = null;
        String err = null;
        try {
            r = kakao.directions(l.getLat(), l.getLng(), d.getDestLat(), d.getDestLng());
        } catch (KakaoClient.KakaoApiException ex) {
            err = ex.code() + ": " + ex.getMessage();
        } catch (RuntimeException ex) {
            err = "ETA_ERROR: " + ex.getClass().getSimpleName();
        }
        final KakaoClient.DirectionsResult res = r;
        final String error = err;
        DeliveryEta saved = tx.execute(s -> {
            DeliveryEta e = etas.findById(deliveryId).orElseGet(() -> new DeliveryEta(deliveryId));
            if (res != null) e.success(PROVIDER, res.durationSeconds(), res.distanceMeters(), now);
            else e.failure(error, now);
            return etas.save(e);
        });
        if (error != null) log.info("ETA 계산 실패 delivery={} {}", deliveryId, error);
        events.publishEvent(DeliveryChanged.of(deliveryId, "ETA"));
        return saved;
    }

    @PreDestroy
    void shutdown() { executor.shutdownNow(); }
}
