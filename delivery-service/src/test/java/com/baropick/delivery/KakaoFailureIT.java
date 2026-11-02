package com.baropick.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baropick.delivery.eta.EtaService;
import com.baropick.delivery.support.IntegrationTestBase;
import com.baropick.delivery.testsupport.simulation.SimulationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;

/**
 * D-11 / D-31: 카카오 API 장애(쿼터 초과·경로 없음·서버 오류·응답 지연·비정상 응답)를 로컬 가짜 서버로 재현.
 * 실제 카카오를 호출하지 않으며, 가짜 서버 응답 형식은 KakaoClient 가 해석하는 필드만 흉내 낸다.
 * 검증: 가짜 정상 ETA 를 보여주지 않는다 / 시뮬레이터는 직선 경로로 대체하지 않고 시나리오를 차단한다.
 */
class KakaoFailureIT extends IntegrationTestBase {

    enum Mode { OK, SLOW_OK, QUOTA, NO_ROUTE, SERVER_ERROR, SLOW, MALFORMED }

    static volatile Mode mode = Mode.OK;
    static final AtomicInteger calls = new AtomicInteger();
    static final HttpServer stub;

    static {
        try {
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        stub.createContext("/v1/directions", KakaoFailureIT::directions);
        stub.createContext("/v2/local/search", KakaoFailureIT::local);
        stub.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); // 지연 응답이 다음 요청을 막지 않게
        stub.start();
    }

    @DynamicPropertySource
    static void kakao(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();
        r.add("kakao.rest-api-key", () -> "test-dummy-key-not-real");
        r.add("kakao.mobility-base-url", () -> base);
        r.add("kakao.local-base-url", () -> base);
        r.add("kakao.read-timeout-ms", () -> "800");
    }

    @AfterAll
    static void stopStub() { stub.stop(0); }

    @Autowired EtaService eta;
    @Autowired SimulationService sim;

    @BeforeEach
    void reset() {
        mode = Mode.OK;
        calls.set(0);
    }

    // ------------------------------------------------------------------ 가짜 카카오

    private static void directions(HttpExchange ex) throws IOException {
        calls.incrementAndGet();
        Mode m = mode;
        if (m == Mode.SLOW_OK) {
            try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            m = Mode.OK;
        }
        switch (m) {
            case SLOW_OK, OK -> {
                // origin/destination 쿼리(경도,위도)를 그대로 잇는 3점 경로, 600초 / 1500m
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                String[] o = q.get("origin").split(","), d = q.get("destination").split(",");
                double mx = (Double.parseDouble(o[0]) + Double.parseDouble(d[0])) / 2, my = (Double.parseDouble(o[1]) + Double.parseDouble(d[1])) / 2;
                send(ex, 200, """
                        {"trans_id":"stub-1","routes":[{"result_code":0,"result_msg":"길찾기 성공",
                          "summary":{"distance":1500,"duration":600},
                          "sections":[{"roads":[{"vertexes":[%s,%s,%s,%s,%s,%s]}]}]}]}
                        """.formatted(o[0], o[1], mx, my, d[0], d[1]));
            }
            case QUOTA -> send(ex, 429, "{\"errorType\":\"RateLimitExceeded\",\"message\":\"API limit has been exceeded.\"}");
            case NO_ROUTE -> send(ex, 200, "{\"trans_id\":\"stub-2\",\"routes\":[{\"result_code\":104,\"result_msg\":\"출발지와 도착지가 5 m 이내로 설정된 경우 경로를 탐색할 수 없음\"}]}");
            case SERVER_ERROR -> send(ex, 500, "{\"errorType\":\"InternalServerError\",\"message\":\"temporary\"}");
            case SLOW -> {
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                send(ex, 200, "{}");
            }
            case MALFORMED -> send(ex, 200, "{\"trans_id\":\"stub-3\",\"routes\":[{\"result_code\":0,\"summary\":{\"distance\":1,\"duration\":1},\"sections\":[{\"roads\":[{\"vertexes\":[129.1,35.1,129.2]}]}]}]}");
        }
    }

    private static void local(HttpExchange ex) throws IOException {
        calls.incrementAndGet();
        if (mode == Mode.QUOTA) send(ex, 429, "{\"errorType\":\"RateLimitExceeded\",\"message\":\"API limit has been exceeded.\"}");
        else send(ex, 200, "{\"documents\":[]}");
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new java.util.HashMap<>();
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    // ------------------------------------------------------------------ 도우미

    private String nearInTransit() {
        Resp o = createOrder("C-TEST-01", NEAR_MFC, 35.1810, 129.0790, 2000, 1);
        String d = o.text("/deliveries/0/deliveryId");
        assertThat(post("R-TEST-01", "/api/rider/me/location", Map.of("latitude", 35.1797, "longitude", 129.0757, "accuracyMeters", 8,
                "measuredAt", clock.instant().toString())).status()).isEqualTo(200);
        pickUp(d, "R-TEST-01");
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/start", null).status()).isEqualTo(200);
        Map<String, Object> p = point(1, 35.1800, 129.0760, clock.instant());
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/locations", Map.of("points", List.of(p))).status()).isEqualTo(200);
        // 위치 반영이 띄운 비동기 ETA 계산이 끝날 때까지 대기 (이후 각 테스트가 모드를 바꿔 직접 재계산)
        for (int i = 0; i < 50; i++) {
            Integer n = jdbcTemplate.queryForObject("select count(*) from delivery_eta where delivery_id=?", Integer.class, d);
            if (n != null && n > 0) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return d;
    }

    private JsonNode customerEta(String d) {
        String order = get("ADMIN-TEST", "/test-api/admin/deliveries/" + d).text("/delivery/orderId");
        return get("C-TEST-01", "/api/customer/orders/" + order + "/tracking").at("/deliveries/0/eta");
    }

    private JsonNode etaAfter(String d, Mode m) {
        mode = m;
        eta.recomputeNow(d);
        return customerEta(d);
    }

    private String newKakaoScenario(String d) {
        Resp r = post("ADMIN-TEST", "/test-api/simulations", Map.of("deliveryId", d, "riderId", "R-TEST-01", "routeSource", "KAKAO_DIRECTIONS"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.text("/scenarioId");
    }

    // ------------------------------------------------------------------ D-11 ETA

    @Test
    @DisplayName("D-11 정상 ETA 후 쿼터 초과 → UNAVAILABLE, 이전 ETA(600초)를 정상값처럼 보여주지 않음")
    void quotaAfterSuccessHidesOldEta() {
        String d = nearInTransit();
        JsonNode ok = etaAfter(d, Mode.OK);
        assertThat(ok.path("status").asString()).isEqualTo("OK");
        assertThat(ok.path("etaSeconds").asInt()).isEqualTo(600);

        JsonNode q = etaAfter(d, Mode.QUOTA);
        assertThat(q.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(q.path("etaSeconds").isNull() || q.path("etaSeconds").isMissingNode()).isTrue();
        assertThat(q.path("estimatedArrivalAt").isNull() || q.path("estimatedArrivalAt").isMissingNode()).isTrue();
        assertThat(q.path("reasonCode").asString()).isEqualTo("KAKAO_QUOTA_EXCEEDED");
        // 고객 문구에는 내부 오류 원문·코드를 넣지 않는다
        assertThat(q.path("message").asString()).doesNotContain("KAKAO").doesNotContain("429").contains("확인할 수 없습니다");

        // 복구되면 다시 정상
        assertThat(etaAfter(d, Mode.OK).path("status").asString()).isEqualTo("OK");
    }

    @Test
    @DisplayName("D-11 경로 없음 / 서버 오류 / 응답 지연 / 비정상 응답 → 각각 실패 사유와 함께 UNAVAILABLE")
    void etaFailureKinds() {
        String d = nearInTransit();
        assertThat(etaAfter(d, Mode.NO_ROUTE).path("reasonCode").asString()).isEqualTo("ROUTE_NOT_FOUND");
        assertThat(etaAfter(d, Mode.SERVER_ERROR).path("reasonCode").asString()).isEqualTo("KAKAO_HTTP_500");
        assertThat(etaAfter(d, Mode.SLOW).path("reasonCode").asString()).isEqualTo("KAKAO_UNREACHABLE");
        assertThat(etaAfter(d, Mode.MALFORMED).path("reasonCode").asString()).isEqualTo("ROUTE_MALFORMED");
        for (Mode m : List.of(Mode.NO_ROUTE, Mode.SERVER_ERROR, Mode.SLOW, Mode.MALFORMED)) {
            JsonNode e = etaAfter(d, m);
            assertThat(e.path("status").asString()).as(m.name()).isEqualTo("UNAVAILABLE");
            assertThat(e.path("etaSeconds").isNull() || e.path("etaSeconds").isMissingNode()).as(m.name()).isTrue();
        }
    }

    @Test
    @DisplayName("D-11 주소 검색 쿼터 초과 → 503 KAKAO_QUOTA_EXCEEDED (빈 결과로 위장하지 않음)")
    void placeSearchQuota() {
        mode = Mode.QUOTA;
        Resp r = get("C-TEST-01", "/api/customer/places?query=" + java.net.URLEncoder.encode("해운대", StandardCharsets.UTF_8));
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.text("/code")).isEqualTo("KAKAO_QUOTA_EXCEEDED");
    }

    @Test
    @DisplayName("SEC-03 주소 검색 분당 10회: 11번째 429(카카오 미호출), 다른 고객은 영향 없음, 60초 뒤 다시 허용")
    void placeSearchRateLimit() {
        String q = "/api/customer/places?query=" + java.net.URLEncoder.encode("해운대", StandardCharsets.UTF_8);
        mclock().advance(java.time.Duration.ofHours(1)); // 다른 테스트의 호출 기록과 겹치지 않게
        for (int i = 1; i <= 10; i++) assertThat(get("C-TEST-02", q).status()).as("call %d", i).isEqualTo(200);
        int kakaoCalls = calls.get();
        Resp r = get("C-TEST-02", q);
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.text("/code")).isEqualTo("RATE_LIMITED");
        assertThat(r.body().path("details").path("retryAfterSeconds").asLong()).isBetween(1L, 61L);
        assertThat(calls.get()).as("거절된 호출은 카카오로 가지 않음").isEqualTo(kakaoCalls);
        assertThat(get("C-TEST-01", q).status()).isEqualTo(200);
        mclock().advance(java.time.Duration.ofSeconds(30));
        assertThat(get("C-TEST-02", q).status()).as("30초 뒤에도 아직 10회가 구간 안").isEqualTo(429);
        mclock().advance(java.time.Duration.ofSeconds(31));
        assertThat(get("C-TEST-02", q).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("ETA 첫 계산이 동시에 여러 번 실행돼도 기본키 충돌 없이 한 행으로 수렴 (회귀: delivery_eta 중복 INSERT)")
    void concurrentFirstComputation() throws Exception {
        Resp o = createOrder("C-TEST-01", NEAR_MFC, 35.1810, 129.0790, 2000, 1);
        String d = o.text("/deliveries/0/deliveryId");
        assertThat(post("R-TEST-01", "/api/rider/me/location", Map.of("latitude", 35.1797, "longitude", 129.0757, "accuracyMeters", 8,
                "measuredAt", clock.instant().toString())).status()).isEqualTo(200);
        pickUp(d, "R-TEST-01");
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/start", null).status()).isEqualTo(200);
        mode = Mode.SLOW_OK; // 카카오 응답을 늦춰 여러 계산의 저장 시점이 겹치게 한다
        // 위치 반영 → 비동기 계산 1건 + 직접 계산 3건 동시
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/locations",
                Map.of("points", List.of(point(1, 35.1800, 129.0760, clock.instant())))).status()).isEqualTo(200);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) fs.add(pool.submit(() -> { start.await(); return eta.recomputeNow(d); }));
        start.countDown();
        for (var f : fs) f.get(); // 예외(기본키 충돌)가 있으면 여기서 실패
        pool.shutdown();
        Thread.sleep(800); // 비동기 계산 종료 대기
        assertThat(jdbcTemplate.queryForObject("select count(*) from delivery_eta where delivery_id=?", Integer.class, d)).isEqualTo(1);
        assertThat(customerEta(d).path("status").asString()).isEqualTo("OK");
    }

    // ------------------------------------------------------------------ D-31 시뮬레이터 경로

    @Test
    @DisplayName("D-31 카카오 쿼터 초과 / 경로 없음 / 비정상 응답 → 시나리오 차단(FAILED), 직선 경로 대체 없음 / 정상이면 실경로로 READY")
    void simulatorRouteFailures() {
        Resp o = createOrder("C-TEST-01", REMOTE_MFC, T1_LAT, T1_LNG, 2000, 1);
        String d = o.text("/deliveries/0/deliveryId");

        for (var c : List.of(Map.entry(Mode.QUOTA, "KAKAO_QUOTA_EXCEEDED"), Map.entry(Mode.NO_ROUTE, "ROUTE_NOT_FOUND"),
                Map.entry(Mode.MALFORMED, "ROUTE_MALFORMED"))) {
            mode = c.getKey();
            String id = newKakaoScenario(d);
            Resp p = post("ADMIN-TEST", "/test-api/simulations/" + id + "/prepare", null);
            assertThat(p.status()).as(c.getKey().name()).isEqualTo(503);
            assertThat(p.text("/code")).isEqualTo(c.getValue());
            var st = sim.status(id);
            assertThat(st.state()).isEqualTo(SimulationService.State.FAILED);
            assertThat(st.totalMeters()).as("직선 대체 경로 없음").isZero();
            assertThat(get("ADMIN-TEST", "/test-api/simulations/" + id + "/route").status()).isNotEqualTo(200);
            assertThat(post("ADMIN-TEST", "/test-api/simulations/" + id + "/start", null).status()).isEqualTo(409);
        }

        mode = Mode.OK;
        String ok = newKakaoScenario(d);
        Resp p = post("ADMIN-TEST", "/test-api/simulations/" + ok + "/prepare", null);
        assertThat(p.status()).as(p.body().toString()).isEqualTo(200);
        assertThat(p.text("/state")).isEqualTo("READY");
        JsonNode route = get("ADMIN-TEST", "/test-api/simulations/" + ok + "/route").body();
        assertThat(route.at("/source").asString()).isEqualTo("KAKAO_DIRECTIONS");
        assertThat(route.at("/pointCount").asInt()).isEqualTo(3);
    }
}
