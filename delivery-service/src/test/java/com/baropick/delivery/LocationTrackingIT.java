package com.baropick.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.baropick.delivery.eta.EtaService;
import com.baropick.delivery.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

/** 실 GPS(DEVICE_GPS) 경로의 서버 검증. 실기기 GPS 자체는 검증하지 않는다(별도 [미검증]). */
class LocationTrackingIT extends IntegrationTestBase {

    @Autowired EtaService eta;

    /** 근거리(DEVICE_GPS) 배송: 라이더 대기 위치를 MFC 근처로 보고해야 배차 반경 정책을 통과한다. */
    private String nearDeliveryInTransit(String customer, String rider) {
        Resp o = createOrder(customer, NEAR_MFC, 35.1810, 129.0790, 2000, 1);
        String d = o.text("/deliveries/0/deliveryId");
        Resp loc = post(rider, "/api/rider/me/location", Map.of("latitude", 35.1797, "longitude", 129.0757, "accuracyMeters", 8,
                "measuredAt", clock.instant().toString()));
        assertThat(loc.status()).isEqualTo(200);
        pickUp(d, rider);
        assertThat(post(rider, "/api/rider/deliveries/" + d + "/start", null).status()).isEqualTo(200);
        return d;
    }

    private Resp send(String rider, String d, List<Map<String, Object>> pts) {
        return post(rider, "/api/rider/deliveries/" + d + "/locations", Map.of("points", pts));
    }

    @Test
    @DisplayName("배차 반경(ENFORCE): 위치 미보고·반경 밖 라이더에게는 근거리 MFC 제안 없음")
    void radiusPolicy() {
        Resp o = createOrder("C-TEST-01", NEAR_MFC, 35.1810, 129.0790, 2000, 1);
        String d = o.text("/deliveries/0/deliveryId");
        onDuty("R-TEST-03");
        assertThat(dispatch.tryDispatch(d).result()).isEqualTo("NO_ELIGIBLE_RIDER");
        post("R-TEST-03", "/api/rider/me/location", Map.of("latitude", 35.30, "longitude", 129.30, "measuredAt", clock.instant().toString()));
        assertThat(dispatch.tryDispatch(d).result()).isEqualTo("NO_ELIGIBLE_RIDER");
        post("R-TEST-03", "/api/rider/me/location", Map.of("latitude", 35.1797, "longitude", 129.0757, "measuredAt", clock.instant().toString()));
        assertThat(dispatch.tryDispatch(d).result()).isEqualTo("OFFERED");
    }

    @Test
    @DisplayName("D-08 위도 91 / 경도 181 / 정확도 -1 / NaN → 거부·기록, 최신 위치 불변")
    void d08_invalidCoordinates() {
        String d = nearDeliveryInTransit("C-TEST-01", "R-TEST-01");
        Instant t = clock.instant();
        Resp ok = send("R-TEST-01", d, List.of(point(1, 35.1800, 129.0760, t)));
        assertThat(ok.text("/points/0/verdict")).isEqualTo("ACCEPTED_LATEST");
        Map<String, Object> nan = point(5, 35.18, 129.07, t.plusSeconds(4));
        nan.put("latitude", "NaN");
        Map<String, Object> negAcc = point(4, 35.18, 129.07, t.plusSeconds(3));
        negAcc.put("accuracyMeters", -1);
        Resp bad = send("R-TEST-01", d, List.of(point(2, 91, 129.07, t.plusSeconds(1)), point(3, 35.18, 181, t.plusSeconds(2)), negAcc, nan));
        assertThat(bad.status()).as(bad.body().toString()).isEqualTo(200);
        for (JsonNode p : bad.at("/points")) assertThat(p.path("verdict").asString()).isEqualTo("REJECTED_INVALID");
        JsonNode rider = get("C-TEST-01", "/api/customer/orders/" + orderOf(d) + "/tracking").at("/deliveries/0/rider");
        assertThat(rider.path("latitude").asDouble()).isEqualTo(35.1800);
    }

    @Test
    @DisplayName("D-09 역순·중복·긴 지연·미래 시각·순간이동 → 최신 위치 역행 없음")
    void d09_orderingAndDelay() {
        String d = nearDeliveryInTransit("C-TEST-01", "R-TEST-01");
        Instant t = clock.instant();
        send("R-TEST-01", d, List.of(point(10, 35.1800, 129.0760, t)));
        Resp r = send("R-TEST-01", d, List.of(
                point(10, 35.1800, 129.0760, t),                     // 중복
                point(11, 35.1790, 129.0750, t.minusSeconds(5)),     // 역순(과거)
                point(12, 35.1802, 129.0762, t.plusSeconds(3)),      // 정상
                point(13, 35.1803, 129.0763, t.plusSeconds(120)),    // 미래(허용 30초 초과)
                point(14, 35.5000, 129.0763, t.plusSeconds(5))));    // 2초 만에 약 35km 이동
        List<String> v = r.at("/points").valueStream().map(p -> p.path("verdict").asString()).toList();
        assertThat(v).containsExactly("DUPLICATE", "ACCEPTED_HISTORY", "ACCEPTED_LATEST", "REJECTED_FUTURE", "REJECTED_ANOMALY");
        JsonNode rider = get("C-TEST-01", "/api/customer/orders/" + orderOf(d) + "/tracking").at("/deliveries/0/rider");
        assertThat(rider.path("latitude").asDouble()).isEqualTo(35.1802);
        // 긴 지연: 측정 후 121초 지나 도착한 좌표는 STALE (최신 미반영)
        mclock().advance(Duration.ofSeconds(200));
        Resp stale = send("R-TEST-01", d, List.of(point(15, 35.1804, 129.0764, t.plusSeconds(60))));
        assertThat(stale.text("/points/0/verdict")).isEqualTo("STALE");
        JsonNode after = get("C-TEST-01", "/api/customer/orders/" + orderOf(d) + "/tracking").at("/deliveries/0/rider");
        assertThat(after.path("latitude").asDouble()).isEqualTo(35.1802);
        assertThat(after.path("ageSeconds").asLong()).isGreaterThanOrEqualTo(199); // 고객 화면 지연 표시 근거
    }

    @Test
    @DisplayName("D-07 라이더가 다른 배송에 위치 보고 → 거부, 정상 배송 위치 변조 없음")
    void d07_otherDelivery() {
        String d = nearDeliveryInTransit("C-TEST-01", "R-TEST-01");
        Instant t = clock.instant();
        send("R-TEST-01", d, List.of(point(1, 35.1800, 129.0760, t)));
        Resp r = send("R-TEST-02", d, List.of(point(2, 35.1900, 129.0900, t.plusSeconds(1))));
        assertThat(r.status()).isEqualTo(403);
        assertThat(get("C-TEST-01", "/api/customer/orders/" + orderOf(d) + "/tracking").at("/deliveries/0/rider/latitude").asDouble())
                .isEqualTo(35.1800);
    }

    @Test
    @DisplayName("D-06 고객이 타인 orderId 조회 → 404, 라이더/관리자 토큰으로 고객 API 불가")
    void d06_httpOwnership() {
        String d = remoteDelivery("C-TEST-01", 1);
        String orderId = orderOf(d);
        assertThat(get("C-TEST-01", "/api/customer/orders/" + orderId + "/tracking").status()).isEqualTo(200);
        assertThat(get("C-TEST-02", "/api/customer/orders/" + orderId + "/tracking").status()).isEqualTo(404);
        assertThat(get("R-TEST-01", "/api/customer/orders/" + orderId + "/tracking").status()).isEqualTo(403);
        assertThat(get(null, "/api/customer/orders/" + orderId + "/tracking").status()).isEqualTo(401);
        assertThat(get("C-TEST-02", "/test-api/mock-oms/orders/" + orderId).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("D-16 공개 시점 정책·완료 후 위치 전송 거부·고객 위치 비공개")
    void d16_afterCompletion() {
        String d = remoteDelivery("C-TEST-01", 1);
        assign(d, "R-TEST-01");
        // 픽업 전: 고객에게 라이더 위치 비공개(P-05)
        assertThat(get("C-TEST-01", "/api/customer/orders/" + orderOf(d) + "/tracking").at("/deliveries/0/riderLocationVisible").asBoolean()).isFalse();
        String n = nearDeliveryInTransit("C-TEST-02", "R-TEST-02");
        Instant t = clock.instant();
        send("R-TEST-02", n, List.of(point(1, 35.1800, 129.0760, t)));
        assertThat(get("C-TEST-02", "/api/customer/orders/" + orderOf(n) + "/tracking").at("/deliveries/0/rider/latitude").asDouble()).isEqualTo(35.18);
        post("R-TEST-02", "/api/rider/deliveries/" + n + "/arrive-destination", null);
        complete("R-TEST-02", n, "done-" + n, fakeJpeg());
        Resp after = send("R-TEST-02", n, List.of(point(2, 35.1801, 129.0761, t.plusSeconds(2))));
        assertThat(after.status()).isEqualTo(409);
        assertThat(after.text("/code")).isEqualTo("LOCATION_NOT_ACCEPTED_IN_STATE");
        JsonNode done = get("C-TEST-02", "/api/customer/orders/" + orderOf(n) + "/tracking").at("/deliveries/0");
        assertThat(done.path("status").asString()).isEqualTo("DELIVERED");
        assertThat(done.path("riderLocationVisible").asBoolean()).isFalse();
        assertThat(done.path("rider").isNull()).isTrue();
    }

    @Test
    @DisplayName("D-20 / S-07 SIMULATED_ROUTE 배송에 앱(실GPS 경로)으로 위치 전송 → LOCATION_MODE_MISMATCH")
    void d20_modeMismatch() {
        String d = remoteDelivery("C-TEST-01", 1);
        pickUp(d, "R-TEST-01");
        Resp r = send("R-TEST-01", d, List.of(point(1, 35.169, 129.132, clock.instant())));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("/code")).isEqualTo("LOCATION_MODE_MISMATCH");
    }

    @Test
    @DisplayName("D-11 / D-31 경로 API 키 없음 → ETA 'UNAVAILABLE', 가짜 ETA 없음")
    void d11_etaUnavailable() {
        String d = nearDeliveryInTransit("C-TEST-01", "R-TEST-01");
        send("R-TEST-01", d, List.of(point(1, 35.1800, 129.0760, clock.instant())));
        eta.recomputeNow(d);
        JsonNode e = get("C-TEST-01", "/api/customer/orders/" + orderOf(d) + "/tracking").at("/deliveries/0/eta");
        assertThat(e.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(e.path("etaSeconds").isNull()).isTrue();
        assertThat(e.path("message").asString()).contains("KAKAO_KEY_MISSING");
    }

    private String orderOf(String deliveryId) {
        return get("ADMIN-TEST", "/test-api/admin/deliveries/" + deliveryId).text("/delivery/orderId");
    }
}
