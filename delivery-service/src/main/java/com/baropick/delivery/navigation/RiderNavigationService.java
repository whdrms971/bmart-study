package com.baropick.delivery.navigation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Geo;
import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.LatestLocationRepository;
import com.baropick.delivery.domain.LocationMode;
import com.baropick.delivery.kakao.KakaoClient;

/**
 * 라이더 앱 안 길안내 경로 (카카오모빌리티 자동차 길찾기, 서버 키).
 * - 목적지: 픽업 전에는 MFC, 픽업 후에는 배송지(전달지는 픽업 후 공개 원칙 유지).
 * - 출발점: SIMULATED_ROUTE 배송은 서버가 받은 최신 위치(시뮬레이터)만 쓴다(휴대폰이 보낸 좌표 무시).
 *   DEVICE_GPS 배송은 앱이 보낸 현재 좌표, 없으면 서버 최신 위치.
 * - 유료 API 보호: 같은 배송·목적지는 navigation-min-refresh-seconds 안에 다시 조회하지 않고 직전 결과를 돌려준다.
 * - 카카오 실패 시 직선 경로로 대체하지 않는다(외부 카카오내비/카카오맵 안내를 쓰도록 안내).
 * - 길안내 결과로 배송 상태를 바꾸지 않는다.
 */
@Service
public class RiderNavigationService {

    public enum Target { MFC, DESTINATION }

    public record GuideView(double lat, double lng, int type, String guidance, String name, int distanceMeters, int durationSeconds) {}

    public record NavigationView(String deliveryId, Target target, String routeSource, String note, int distanceMeters, int durationSeconds,
                                 Instant computedAt, double originLat, double originLng, String originSource, double targetLat,
                                 double targetLng, List<double[]> points, List<GuideView> guides, boolean cached) {}

    static final Set<DeliveryStatus> TO_MFC = EnumSet.of(DeliveryStatus.ASSIGNED, DeliveryStatus.ARRIVED_AT_MFC);
    static final Set<DeliveryStatus> TO_DESTINATION =
            EnumSet.of(DeliveryStatus.PICKED_UP, DeliveryStatus.IN_TRANSIT, DeliveryStatus.ARRIVED_AT_DESTINATION);
    private static final int CACHE_CLEANUP_THRESHOLD = 2_000;

    private record Entry(NavigationView view, Instant attemptedAt, int assignmentVersion) {}

    private final DeliveryRepository deliveries;
    private final LatestLocationRepository latest;
    private final KakaoClient kakao;
    private final Clock clock;
    private final Integer minRefreshSeconds;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public RiderNavigationService(DeliveryRepository deliveries, LatestLocationRepository latest, KakaoClient kakao, Clock clock,
                                  DeliveryProperties props) {
        this.deliveries = deliveries;
        this.latest = latest;
        this.kakao = kakao;
        this.clock = clock;
        this.minRefreshSeconds = props.policy().navigationMinRefreshSeconds();
    }

    @Transactional(readOnly = true)
    public NavigationView route(String deliveryId, String riderId, Target target, Double lat, Double lng) {
        int minRefresh = DeliveryProperties.Policy.require(minRefreshSeconds, "navigation-min-refresh-seconds");
        Delivery d = deliveries.findById(deliveryId)
                .orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", "배송을 찾을 수 없습니다"));
        if (!riderId.equals(d.getRiderId())) throw ApiException.forbidden("NOT_ASSIGNED_RIDER", "배정된 배송이 아닙니다");
        DeliveryStatus s = d.getStatus();
        if (s.isTerminal()) throw ApiException.conflict("DELIVERY_FINISHED", "종료된 배송입니다");
        if (target == Target.MFC && !TO_MFC.contains(s)) {
            throw ApiException.conflict("NAVIGATION_TARGET_NOT_ACTIVE", "픽업이 끝나 MFC 길안내를 제공하지 않습니다");
        }
        if (target == Target.DESTINATION && !TO_DESTINATION.contains(s)) {
            throw ApiException.conflict("NAVIGATION_TARGET_NOT_ACTIVE", "배송지 길안내는 전 포장 픽업 후에 제공됩니다");
        }

        double[] origin = origin(d, lat, lng);
        String originSource = origin.length == 3 ? "DEVICE" : "SERVER_LATEST";
        double tLat = target == Target.MFC ? d.getOriginLat() : d.getDestLat();
        double tLng = target == Target.MFC ? d.getOriginLng() : d.getDestLng();

        Instant now = clock.instant();
        String key = deliveryId + ':' + target + ':' + riderId;
        Entry prev = cache.get(key);
        if (prev != null && prev.assignmentVersion() == d.getAssignmentVersion()
                && prev.attemptedAt().plusSeconds(minRefresh).isAfter(now)) {
            if (prev.view() != null) return withCached(prev.view());
            long retry = Math.max(1, Duration.between(now, prev.attemptedAt().plusSeconds(minRefresh)).toSeconds() + 1);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "NAVIGATION_RATE_LIMITED",
                    "길안내 경로 재조회는 " + minRefresh + "초에 한 번 가능합니다", Map.of("retryAfterSeconds", retry));
        }
        if (cache.size() > CACHE_CLEANUP_THRESHOLD) {
            Instant old = now.minus(Duration.ofHours(1));
            cache.values().removeIf(e -> e.attemptedAt().isBefore(old));
        }
        // 실패도 기록해 카카오 장애 중 반복 호출을 막는다
        cache.put(key, new Entry(prev == null || prev.assignmentVersion() != d.getAssignmentVersion() ? null : prev.view(), now,
                d.getAssignmentVersion()));
        KakaoClient.DirectionsResult r;
        try {
            r = kakao.directions(origin[0], origin[1], tLat, tLng);
        } catch (KakaoClient.KakaoApiException e) {
            throw ApiException.unavailable(e.code(), "길안내 경로를 지금 조회할 수 없습니다. 카카오내비 또는 카카오맵 길안내를 이용하세요");
        }
        NavigationView v = new NavigationView(deliveryId, target, "KAKAO_DIRECTIONS",
                "카카오모빌리티 자동차 경로 기준 — 이륜차 전용·통행 제한 도로 정보는 반영되지 않습니다", r.distanceMeters(), r.durationSeconds(),
                r.fetchedAt(), origin[0], origin[1], originSource, tLat, tLng,
                r.vertexes().stream().map(p -> new double[] {p.lat(), p.lng()}).toList(),
                r.guides().stream().map(g -> new GuideView(g.lat(), g.lng(), g.type(), g.guidance(), g.name(), g.distanceMeters(),
                        g.durationSeconds())).toList(),
                false);
        cache.put(key, new Entry(v, now, d.getAssignmentVersion()));
        return v;
    }

    /** [lat, lng] (서버 최신 위치) 또는 [lat, lng, 1] (기기 좌표). */
    private double[] origin(Delivery d, Double lat, Double lng) {
        if (d.getLocationMode() == LocationMode.DEVICE_GPS && (lat != null || lng != null)) {
            if (!Geo.validLat(lat) || !Geo.validLng(lng)) throw ApiException.badRequest("INVALID_COORDINATE", "현재 위치 좌표 범위 오류");
            return new double[] {lat, lng, 1};
        }
        return latest.findById(d.getId())
                .map(l -> new double[] {l.getLat(), l.getLng()})
                .orElseThrow(() -> ApiException.conflict("NO_CURRENT_LOCATION",
                        d.getLocationMode() == LocationMode.SIMULATED_ROUTE
                                ? "시뮬레이터 위치가 아직 없습니다 (SIMULATED 배송은 재생이 시작된 뒤 길안내가 가능합니다)"
                                : "현재 위치를 아직 받지 못했습니다. 위치 권한·GPS 를 확인하세요"));
    }

    private static NavigationView withCached(NavigationView v) {
        return new NavigationView(v.deliveryId(), v.target(), v.routeSource(), v.note(), v.distanceMeters(), v.durationSeconds(), v.computedAt(),
                v.originLat(), v.originLng(), v.originSource(), v.targetLat(), v.targetLng(), v.points(), v.guides(), true);
    }
}
