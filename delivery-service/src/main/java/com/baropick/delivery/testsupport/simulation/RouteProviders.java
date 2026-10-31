package com.baropick.delivery.testsupport.simulation;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Geo;
import com.baropick.delivery.kakao.KakaoClient;
import com.baropick.delivery.testsupport.simulation.SimRoute.Pt;
import com.baropick.delivery.testsupport.simulation.SimRoute.Source;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 경로 공급자: FIXTURE(자체 작성, 키 없이 반복 재생) / KAKAO_DIRECTIONS(서버에서 실제 호출).
 * 둘 다 "MFC 고정 좌표 → 주문 시점 배송지 스냅샷" 구간만 허용한다. 직선을 도로 경로로 포장하지 않는다.
 */
@Component
@Profile("local-test")
public class RouteProviders {
    /** FIXTURE 시작/끝이 MFC·배송지와 이 거리 이내여야 한다. */
    public static final double ENDPOINT_TOLERANCE_M = 60;

    public record Fixture(String id, String description, String provenance, String mfcId, String destinationLabel, String destinationAddress,
                          double destLat, double destLng, double defaultSpeedMps, List<Pt> points) {}

    private final Map<String, Fixture> fixtures = new LinkedHashMap<>();
    private final KakaoClient kakao;
    private final Clock clock;

    public RouteProviders(JsonMapper json, KakaoClient kakao, Clock clock) throws IOException {
        this.kakao = kakao;
        this.clock = clock;
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:fixtures/routes/*.json")) {
            try (InputStream in = r.getInputStream()) {
                JsonNode n = json.readTree(in);
                List<Pt> pts = new ArrayList<>();
                for (JsonNode p : n.path("points")) pts.add(new Pt(p.get(0).asDouble(), p.get(1).asDouble()));
                JsonNode d = n.path("destination");
                Fixture f = new Fixture(n.path("id").asString(), n.path("description").asString(""), n.path("provenance").asString(""),
                        n.path("mfcId").asString(), d.path("label").asString(""), d.path("address").asString(""), d.path("latitude").asDouble(),
                        d.path("longitude").asDouble(), n.path("defaultSpeedMps").asDouble(6.0), pts);
                fixtures.put(f.id(), f);
            }
        }
    }

    public List<Fixture> fixtures() { return List.copyOf(fixtures.values()); }

    public SimRoute fixture(String fixtureId, double originLat, double originLng, double destLat, double destLng) {
        Fixture f = fixtures.get(fixtureId);
        if (f == null) throw ApiException.badRequest("FIXTURE_NOT_FOUND", "없는 FIXTURE: " + fixtureId);
        Pt first = f.points().get(0), last = f.points().get(f.points().size() - 1);
        double startGap = Geo.distanceMeters(first.lat(), first.lng(), originLat, originLng);
        double endGap = Geo.distanceMeters(last.lat(), last.lng(), destLat, destLng);
        if (startGap > ENDPOINT_TOLERANCE_M || endGap > ENDPOINT_TOLERANCE_M) {
            throw ApiException.conflict("ROUTE_ENDPOINT_MISMATCH", String.format(
                    "FIXTURE 경로 시작/끝이 MFC·배송지 스냅샷과 다릅니다(시작 %.0fm, 끝 %.0fm). FIXTURE 전용 테스트 배송지를 쓰거나 KAKAO_DIRECTIONS 를 선택하세요",
                    startGap, endGap));
        }
        return new SimRoute(Source.FIXTURE, f.id(), f.points(), null, clock.instant(), f.provenance());
    }

    public SimRoute kakao(double originLat, double originLng, double destLat, double destLng) {
        try {
            var r = kakao.directions(originLat, originLng, destLat, destLng);
            List<Pt> pts = r.vertexes().stream().map(v -> new Pt(v.lat(), v.lng())).toList();
            return new SimRoute(Source.KAKAO_DIRECTIONS, r.transId(), pts, r.durationSeconds(), r.fetchedAt(),
                    "카카오모빌리티 자동차 길찾기 실호출 결과 (메모리에만 보관, 오토바이 합법 경로 아님). 거리 " + r.distanceMeters() + "m");
        } catch (KakaoClient.KakaoApiException e) {
            // 임의 직선 대체 금지: 시나리오 차단
            throw ApiException.unavailable(e.code(), "카카오 경로 조회 실패 — 시나리오 차단(직선 대체 없음): " + e.getMessage());
        }
    }
}
