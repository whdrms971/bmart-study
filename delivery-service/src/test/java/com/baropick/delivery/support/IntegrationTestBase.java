package com.baropick.delivery.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import com.baropick.delivery.dispatch.DispatchService;
import com.baropick.delivery.outbox.OutboxRelay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 HTTP(랜덤 포트)로 Delivery·Mock OMS/MFC·시뮬레이터 API 를 호출하는 통합 테스트 기반.
 * 스케줄러(배차/시뮬레이터/outbox)는 끄고 테스트가 결정적으로 직접 구동한다. 카카오 키는 비워 실호출하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.main.allow-bean-definition-overriding=true",
        "delivery.test-accounts.passcode=test-passcode-1",
        "delivery.dispatch.scheduler-enabled=false",
        "delivery.simulation.auto-tick=false",
        "delivery.outbox.relay-interval-ms=3600000",
        "delivery.policy.proof-storage-dir=build/test-proofs",
        "delivery.simulation.log-dir=build/test-sim-logs",
        "kakao.rest-api-key="
})
@ActiveProfiles("local-test")
@org.springframework.context.annotation.Import(IntegrationTestBase.ClockConfig.class)
public abstract class IntegrationTestBase {

    /**
     * 테스트 DB 전환: Gradle `-PtestDbUrl=jdbc:postgresql://… -PtestDbUser=… -PtestDbPassword=…` 이면 H2 대신 그 DB 를 쓴다
     * (scripts/test-postgres.ps1). 행 잠금·동시성 테스트(D-02, D-12 등)를 운영 DB 엔진에서 검증하기 위함.
     */
    @org.springframework.test.context.DynamicPropertySource
    static void testDatabase(org.springframework.test.context.DynamicPropertyRegistry r) {
        String url = System.getProperty("delivery.test.db.url", "");
        if (url.isBlank()) return;
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", () -> System.getProperty("delivery.test.db.user", ""));
        r.add("spring.datasource.password", () -> System.getProperty("delivery.test.db.password", ""));
    }

    public static final String REMOTE_MFC = "MFC-TEST-REMOTE-01";
    public static final String NEAR_MFC = "MFC-TEST-01";
    public static final double T1_LAT = 35.162100, T1_LNG = 129.160200;
    public static final String FIXTURE = "BUSAN-CENTUM-HAEUNDAE-T1";

    @TestConfiguration
    public static class ClockConfig {
        @Bean
        @Primary
        public MutableClock testClock() { return new MutableClock(); }
    }

    @LocalServerPort protected int port;
    @Autowired protected JsonMapper json;
    @Autowired protected Clock clock;
    @Autowired protected DispatchService dispatch;
    @Autowired protected OutboxRelay relay;

    protected RestClient http;
    private final Map<String, String> tokens = new HashMap<>();

    public record Resp(int status, JsonNode body) {
        public JsonNode at(String ptr) { return body.at(ptr); }
        public String text(String ptr) { return body.at(ptr).asString(); }
    }

    @Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired protected com.baropick.delivery.testsupport.mockmfc.MockMfcService mockMfc;
    @Autowired protected com.baropick.delivery.testsupport.mockoms.MockOmsService mockOms;

    private static final List<String> TABLES = List.of("rider_location", "delivery_latest_location", "delivery_eta", "delivery_proof",
            "outbox_event", "idempotency_record", "inbox_event", "delivery_status_history", "dispatch_offer", "dispatch_attempt",
            "delivery_package", "delivery_arrival_exception", "delivery", "mock_oms_inbox", "mock_oms_order_delivery", "mock_oms_order", "mock_mfc_package");

    @BeforeEach
    void setUpHttp() {
        http = RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> {}).build();
        ((MutableClock) clock).set(Instant.now());
        // 테스트 간 격리: 데이터 초기화(같은 컨텍스트 재사용), 라이더 운행 종료, Mock 장애 해제
        for (String t : TABLES) jdbcTemplate.update("delete from " + t);
        jdbcTemplate.update("update rider set duty_status='OFF_DUTY', last_lat=null, last_lng=null, last_location_at=null");
        mockMfc.setFault(com.baropick.delivery.testsupport.mockmfc.MockMfcService.HandoverFault.NONE);
        mockOms.setEventFault(com.baropick.delivery.testsupport.mockoms.MockOmsService.EventFault.NONE, 0);
    }

    protected MutableClock mclock() { return (MutableClock) clock; }

    protected String token(String userId) {
        return tokens.computeIfAbsent(userId, u -> {
            Resp r = post(null, "/api/auth/test-login", Map.of("userId", u, "passcode", "test-passcode-1"));
            assertThat(r.status()).as("login " + u).isEqualTo(200);
            return r.text("/accessToken");
        });
    }

    protected Resp toResp(ResponseEntity<String> e) {
        String b = e.getBody();
        return new Resp(e.getStatusCode().value(), b == null || b.isBlank() ? json.readTree("{}") : json.readTree(b));
    }

    protected Resp post(String userId, String path, Object body) {
        var spec = http.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (userId != null) spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId));
        if (body != null) spec = spec.body(body);
        return toResp(spec.retrieve().toEntity(String.class));
    }

    protected Resp put(String userId, String path, Object body) {
        return toResp(http.put().uri(path).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                .body(body).retrieve().toEntity(String.class));
    }

    protected Resp get(String userId, String path) {
        var spec = http.get().uri(path);
        if (userId != null) spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId));
        return toResp(spec.retrieve().toEntity(String.class));
    }

    // ------------------------------------------------------------ 시나리오 헬퍼

    protected Resp createOrder(String customer, String mfcId, double lat, double lng, int weightGrams, int packages) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("mfcId", mfcId);
        order.put("latitude", lat);
        order.put("longitude", lng);
        order.put("address", "테스트 배송지");
        order.put("addressDetail", "101호");
        order.put("totalWeightGrams", weightGrams);
        order.put("packageCount", packages);
        order.put("autoSeal", false);
        Resp r = post(customer, "/test-api/mock-oms/orders", Map.of("order", order));
        assertThat(r.status()).as("create order: " + r.body()).isEqualTo(200);
        return r;
    }

    /** 원거리(시뮬레이션) 테스트 주문 1건 → deliveryId. */
    protected String remoteDelivery(String customer, int packages) {
        Resp o = createOrder(customer, REMOTE_MFC, T1_LAT, T1_LNG, 3000, packages);
        return o.text("/deliveries/0/deliveryId");
    }

    protected void onDuty(String rider) {
        Resp r = post(rider, "/api/rider/me/duty", Map.of("dutyStatus", "ON_DUTY_ACCEPTING"));
        assertThat(r.status()).isEqualTo(200);
    }

    protected String offerFor(String rider, String deliveryId) {
        Resp r = get(rider, "/api/rider/offers");
        for (JsonNode o : r.body()) if (o.path("deliveryId").asString().equals(deliveryId)) return o.path("offerId").asString();
        return null;
    }

    /** 배차 → 수락까지. */
    protected void assign(String deliveryId, String rider) {
        onDuty(rider);
        dispatch.tryDispatch(deliveryId);
        String offer = offerFor(rider, deliveryId);
        assertThat(offer).as("offer for " + rider).isNotNull();
        Resp a = post(rider, "/api/rider/offers/" + offer + "/accept", null);
        assertThat(a.status()).as("accept " + a.body()).isEqualTo(200);
    }

    protected List<String> qrCodes(String deliveryId) {
        Resp r = get("ADMIN-TEST", "/test-api/mock-mfc/deliveries/" + deliveryId + "/packages");
        List<String> out = new ArrayList<>();
        for (JsonNode p : r.body()) out.add(p.path("qrCode").asString());
        return out;
    }

    protected void seal(String deliveryId) {
        Resp r = post("ADMIN-TEST", "/test-api/mock-mfc/deliveries/" + deliveryId + "/seal", null);
        assertThat(r.status()).as("seal " + r.body()).isEqualTo(200);
    }

    /** 배정 → MFC 도착 → 포장 완료 → 전 포장 스캔 → PICKED_UP. */
    protected void pickUp(String deliveryId, String rider) {
        assign(deliveryId, rider);
        assertThat(post(rider, "/api/rider/deliveries/" + deliveryId + "/arrive-mfc", null).status()).isEqualTo(200);
        seal(deliveryId);
        for (String qr : qrCodes(deliveryId)) {
            Resp s = post(rider, "/api/rider/deliveries/" + deliveryId + "/handover-scans", Map.of("qrCode", qr));
            assertThat(s.status()).as("scan " + s.body()).isEqualTo(200);
        }
        assertThat(status(deliveryId)).isEqualTo("PICKED_UP");
    }

    protected String status(String deliveryId) {
        return get("ADMIN-TEST", "/test-api/admin/deliveries/" + deliveryId).text("/delivery/status");
    }

    protected Resp complete(String rider, String deliveryId, String requestId, byte[] photo) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("requestId", requestId);
        form.add("method", "NON_FACE_TO_FACE");
        if (photo != null) {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.IMAGE_JPEG);
            form.add("photo", new org.springframework.http.HttpEntity<>(new ByteArrayResource(photo) {
                @Override public String getFilename() { return "proof.jpg"; }
            }, h));
        }
        return toResp(http.post().uri("/api/rider/deliveries/" + deliveryId + "/complete").contentType(MediaType.MULTIPART_FORM_DATA)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(rider)).body(form).retrieve().toEntity(String.class));
    }

    protected static byte[] fakeJpeg() {
        byte[] b = new byte[64];
        b[0] = (byte) 0xFF; b[1] = (byte) 0xD8; b[2] = (byte) 0xFF; b[3] = (byte) 0xE0;
        b[62] = (byte) 0xFF; b[63] = (byte) 0xD9;
        return b;
    }

    @Autowired protected com.baropick.delivery.tracking.LocationIngestService ingestService;
    private final Map<String, Long> placeSeq = new HashMap<>();

    /**
     * 배송의 최신 위치를 지정 좌표로 만든다. 실 GPS 배송은 라이더 위치 API, SIMULATED_ROUTE 배송은 시뮬레이터와 같은 서버 내부 수집 경로.
     * 반환: 수집 판정(ACCEPTED_LATEST 등).
     */
    protected String placeAt(String deliveryId, String rider, double lat, double lng, Double accuracy, Instant measuredAt) {
        long seq = placeSeq.merge(deliveryId, 1L, Long::sum) + 1000;
        Resp v = get(rider, "/api/rider/deliveries/" + deliveryId);
        boolean simulated = v.body().at("/simulated").asBoolean();
        if (simulated) {
            var res = ingestService.ingest(new com.baropick.delivery.tracking.LocationIngestService.IngestContext(deliveryId, rider,
                    com.baropick.delivery.domain.LocationMode.SIMULATED_ROUTE, "TEST-HELPER", measuredAt, v.body().at("/assignmentVersion").asInt()),
                    List.of(new com.baropick.delivery.tracking.LocationIngestService.LocationPoint(seq, lat, lng, accuracy, null, null, measuredAt)));
            return res.points().get(0).verdict().name();
        }
        Map<String, Object> p = point(seq, lat, lng, measuredAt);
        p.put("accuracyMeters", accuracy);
        Resp r = post(rider, "/api/rider/deliveries/" + deliveryId + "/locations", Map.of("points", List.of(p)));
        assertThat(r.status()).as("locations " + r.body()).isEqualTo(200);
        return r.body().at("/points/0/verdict").asString();
    }

    /** 배송지 좌표 (라이더 화면 기준). */
    protected double[] destinationOf(String deliveryId, String rider) {
        Resp v = get(rider, "/api/rider/deliveries/" + deliveryId);
        return new double[] {v.body().at("/destination/latitude").asDouble(), v.body().at("/destination/longitude").asDouble()};
    }

    /** 배송지 좌표에서 정북 방향으로 meters 떨어진 좌표 (Geo 와 같은 지구 반경 → 대권 거리 = meters). */
    protected static double[] northOf(double lat, double lng, double meters) {
        return new double[] {lat + Math.toDegrees(meters / 6_371_008.8), lng};
    }

    /** 배송지에 정확한 최신 위치를 둔 뒤 고객 도착. */
    protected Resp arriveHere(String rider, String deliveryId) {
        double[] dest = destinationOf(deliveryId, rider);
        placeAt(deliveryId, rider, dest[0], dest[1], 5.0, clock.instant());
        return post(rider, "/api/rider/deliveries/" + deliveryId + "/arrive-destination", null);
    }

    protected Map<String, Object> point(long seq, double lat, double lng, Instant measuredAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("latitude", lat);
        m.put("longitude", lng);
        m.put("accuracyMeters", 5.0);
        m.put("measuredAt", measuredAt.toString());
        return m;
    }
}
