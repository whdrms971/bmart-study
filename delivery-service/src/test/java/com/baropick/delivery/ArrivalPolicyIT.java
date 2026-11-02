package com.baropick.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.baropick.delivery.support.IntegrationTestBase;

/**
 * 고객 도착 판정 정책 (DECISIONS.md A-01~A-04): 150m / 측정 후 30초 / 정확도 50m, 관리자 예외 승인, 상태·권한·멱등.
 */
class ArrivalPolicyIT extends IntegrationTestBase {

    private static final String RIDER = "R-TEST-01";

    /** 원거리(SIMULATED_ROUTE) 배송을 배송 중까지 진행. */
    private String inTransitRemote() {
        String d = remoteDelivery("C-TEST-01", 1);
        pickUp(d, RIDER);
        assertThat(post(RIDER, "/api/rider/deliveries/" + d + "/start", null).status()).isEqualTo(200);
        return d;
    }

    /** 근거리(실 GPS) 배송을 배송 중까지 진행. */
    private String inTransitNear() {
        Resp o = createOrder("C-TEST-02", NEAR_MFC, 35.1810, 129.0790, 2000, 1);
        String d = o.text("/deliveries/0/deliveryId");
        assertThat(post(RIDER, "/api/rider/me/location", Map.of("latitude", 35.1797, "longitude", 129.0757, "accuracyMeters", 8,
                "measuredAt", clock.instant().toString())).status()).isEqualTo(200);
        pickUp(d, RIDER);
        assertThat(post(RIDER, "/api/rider/deliveries/" + d + "/start", null).status()).isEqualTo(200);
        return d;
    }

    private Resp arrive(String rider, String d) { return post(rider, "/api/rider/deliveries/" + d + "/arrive-destination", null); }

    private void placeAtDistance(String d, double meters, Double accuracy, Instant measuredAt) {
        double[] dest = destinationOf(d, RIDER);
        double[] p = northOf(dest[0], dest[1], meters);
        assertThat(placeAt(d, RIDER, p[0], p[1], accuracy, measuredAt)).isEqualTo("ACCEPTED_LATEST");
    }

    // ------------------------------------------------------------------ 1. 거리

    @ParameterizedTest(name = "{0}m → 허용")
    @ValueSource(doubles = {0, 149, 150})
    @DisplayName("A-01 배송지 직선거리 0 / 149 / 150m → 고객 도착 허용")
    void allowedWithin150(double meters) {
        String d = inTransitRemote();
        placeAtDistance(d, meters, 5.0, clock.instant());
        Resp r = arrive(RIDER, d);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(status(d)).isEqualTo("ARRIVED_AT_DESTINATION");
        String reason = get("ADMIN-TEST", "/test-api/admin/deliveries/" + d).body().at("/history").valueStream()
                .filter(h -> "ARRIVED_AT_DESTINATION".equals(h.at("/toStatus").asString())).findFirst().orElseThrow().at("/reason").asString();
        assertThat(reason).contains("거리 판정 통과").contains("허용 150m");
    }

    @ParameterizedTest(name = "{0}m → 거부")
    @ValueSource(doubles = {151, 480})
    @DisplayName("A-01 배송지 직선거리 151 / 480m → 거부(ARRIVAL_TOO_FAR), 상태 불변")
    void rejectedBeyond150(double meters) {
        String d = inTransitRemote();
        placeAtDistance(d, meters, 5.0, clock.instant());
        Resp r = arrive(RIDER, d);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("/code")).isEqualTo("ARRIVAL_TOO_FAR");
        assertThat(r.at("/details/distanceMeters").asDouble()).isCloseTo(meters, offset(0.05));
        assertThat(r.at("/details/maxDistanceMeters").asDouble()).isEqualTo(150.0);
        assertThat(status(d)).isEqualTo("IN_TRANSIT");
    }

    @Test
    @DisplayName("A-01 실 GPS 배송: 단말 위치 151m 거부 → 149m 허용 (같은 서버 판정)")
    void deviceGpsPath() {
        String d = inTransitNear();
        Instant t = clock.instant();
        placeAtDistance(d, 151, 8.0, t);
        assertThat(arrive(RIDER, d).text("/code")).isEqualTo("ARRIVAL_TOO_FAR");
        mclock().advance(Duration.ofSeconds(2));
        placeAtDistance(d, 149, 8.0, clock.instant());
        assertThat(arrive(RIDER, d).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 2. 위치 데이터 검증

    @Test
    @DisplayName("A-02 위치 없음 → 재측정 요청(LOCATION_REMEASURE_REQUIRED / LOCATION_MISSING)")
    void noLocation() {
        String d = inTransitRemote();
        Resp r = arrive(RIDER, d);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("/code")).isEqualTo("LOCATION_REMEASURE_REQUIRED");
        assertThat(r.text("/details/reason")).isEqualTo("LOCATION_MISSING");
        assertThat(status(d)).isEqualTo("IN_TRANSIT");
    }

    @Test
    @DisplayName("A-02 측정 후 30초까지 허용, 31초 → 재측정 요청(LOCATION_STALE)")
    void staleLocation() {
        String d = inTransitNear();
        placeAtDistance(d, 10, 5.0, clock.instant());
        mclock().advance(Duration.ofSeconds(31));
        Resp stale = arrive(RIDER, d);
        assertThat(stale.status()).isEqualTo(422);
        assertThat(stale.text("/details/reason")).isEqualTo("LOCATION_STALE");
        assertThat(stale.at("/details/locationAgeSeconds").asLong()).isEqualTo(31);

        // 재측정(새 위치) 후 정확히 30초 경과 → 허용
        placeAtDistance(d, 10, 5.0, clock.instant());
        mclock().advance(Duration.ofSeconds(30));
        assertThat(arrive(RIDER, d).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("A-02 정확도 51m → 재측정 요청(LOCATION_INACCURATE), 50m → 허용")
    void inaccurateLocation() {
        String d = inTransitRemote();
        placeAtDistance(d, 0, 51.0, clock.instant());
        Resp bad = arrive(RIDER, d);
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.text("/details/reason")).isEqualTo("LOCATION_INACCURATE");
        assertThat(bad.at("/details/accuracyMeters").asDouble()).isEqualTo(51.0);
        mclock().advance(Duration.ofSeconds(1));
        placeAtDistance(d, 0, 50.0, clock.instant());
        assertThat(arrive(RIDER, d).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("A-02 정확도 정보 없음 → 재측정 요청")
    void missingAccuracy() {
        String d = inTransitRemote();
        placeAtDistance(d, 0, null, clock.instant());
        assertThat(arrive(RIDER, d).text("/details/reason")).isEqualTo("LOCATION_INACCURATE");
    }

    @Test
    @DisplayName("A-02 이전 담당 라이더가 남긴 최신 위치는 새 담당 라이더 판정에 쓰지 않음 → 재측정 요청")
    void previousRiderLocationIgnored() {
        String d = remoteDelivery("C-TEST-01", 1);
        assign(d, RIDER);
        double[] dest = destinationOf(d, RIDER);
        assertThat(placeAt(d, RIDER, dest[0], dest[1], 5.0, clock.instant())).isEqualTo("ACCEPTED_LATEST");
        // 픽업 전 재배정 → 다른 라이더가 픽업·배송 (새 라이더는 위치 미보고)
        assertThat(post("ADMIN-TEST", "/test-api/admin/deliveries/" + d + "/reassign", Map.of("reason", "테스트")).status()).isEqualTo(200);
        post(RIDER, "/api/rider/me/duty", Map.of("dutyStatus", "OFF_DUTY"));
        pickUp(d, "R-TEST-02");
        assertThat(post("R-TEST-02", "/api/rider/deliveries/" + d + "/start", null).status()).isEqualTo(200);
        Resp r = arrive("R-TEST-02", d);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("/details/reason")).isEqualTo("LOCATION_MISSING");
    }

    // ------------------------------------------------------------------ 3. 예외 처리

    @Test
    @DisplayName("A-03 480m 예외 요청 → 관리자 승인(사유 필수) → 도착, 거리·정확도·처리자·시각 감사 기록")
    void adminApprovalAudit() {
        String d = inTransitRemote();
        placeAtDistance(d, 480, 5.0, clock.instant());
        assertThat(arrive(RIDER, d).text("/code")).isEqualTo("ARRIVAL_TOO_FAR");

        Resp req = post(RIDER, "/api/rider/deliveries/" + d + "/arrival-exceptions",
                Map.of("reasonCode", "ADDRESS_COORDINATE_ERROR", "note", "단지 정문 좌표가 실제 동 위치와 다름"));
        assertThat(req.status()).as(req.body().toString()).isEqualTo(200);
        assertThat(req.text("/status")).isEqualTo("PENDING");
        assertThat(req.text("/checkCode")).isEqualTo("TOO_FAR");
        assertThat(req.at("/distanceMeters").asDouble()).isCloseTo(480, offset(0.05));
        String id = req.text("/exceptionId");

        // 중복 요청 → 같은 요청(멱등)
        Resp again = post(RIDER, "/api/rider/deliveries/" + d + "/arrival-exceptions", Map.of("reasonCode", "GPS_ERROR"));
        assertThat(again.text("/exceptionId")).isEqualTo(id);
        assertThat(again.text("/result")).isEqualTo("ALREADY_PENDING");

        // 라이더는 승인 API 접근 불가, 사유 없는 승인 불가
        assertThat(post(RIDER, "/api/admin/arrival-exceptions/" + id + "/approve", Map.of("note", "x")).status()).isEqualTo(403);
        assertThat(post("ADMIN-TEST", "/api/admin/arrival-exceptions/" + id + "/approve", Map.of("note", " ")).text("/code"))
                .isEqualTo("NOTE_REQUIRED");
        assertThat(status(d)).isEqualTo("IN_TRANSIT");

        // 관리자 처리 대기 목록에 노출
        assertThat(get("ADMIN-TEST", "/api/admin/arrival-exceptions?status=PENDING").body().valueStream()
                .anyMatch(e -> id.equals(e.at("/exceptionId").asString()))).isTrue();

        Instant approvedAt = clock.instant();
        Resp ok = post("ADMIN-TEST", "/api/admin/arrival-exceptions/" + id + "/approve", Map.of("note", "고객 통화로 현관 도착 확인"));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(ok.text("/status")).isEqualTo("APPROVED");
        assertThat(ok.text("/decidedBy")).isEqualTo("ADMIN-TEST");
        assertThat(Instant.parse(ok.text("/decidedAt"))).isEqualTo(approvedAt);
        assertThat(ok.text("/decisionNote")).isEqualTo("고객 통화로 현관 도착 확인");
        assertThat(status(d)).isEqualTo("ARRIVED_AT_DESTINATION");

        // DB 감사 기록
        var row = jdbcTemplate.queryForMap("select * from delivery_arrival_exception where id=?", id);
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(((Number) row.get("distance_m")).doubleValue()).isCloseTo(480, offset(0.05));
        assertThat(((Number) row.get("accuracy_m")).doubleValue()).isEqualTo(5.0);
        assertThat(row.get("decided_by")).isEqualTo("ADMIN-TEST");
        assertThat(row.get("reason_code")).isEqualTo("ADDRESS_COORDINATE_ERROR");
        // 상태 이력: 관리자 주체로 도착 전이, 사유·거리·정확도 포함
        var history = get("ADMIN-TEST", "/test-api/admin/deliveries/" + d).body().at("/history");
        var arrived = history.valueStream().filter(h -> "ARRIVED_AT_DESTINATION".equals(h.at("/toStatus").asString())).findFirst().orElseThrow();
        assertThat(arrived.at("/actor").asString()).isEqualTo("ADMIN:ADMIN-TEST");
        assertThat(arrived.at("/reason").asString()).contains(id).contains("ADDRESS_COORDINATE_ERROR").contains("480.0m").contains("5.0m")
                .contains("고객 통화로 현관 도착 확인");

        // 재승인 → 같은 결과, 이후 증빙 완료 가능
        assertThat(post("ADMIN-TEST", "/api/admin/arrival-exceptions/" + id + "/approve", Map.of("note", "again")).text("/result"))
                .isEqualTo("ALREADY_APPROVED");
        assertThat(complete(RIDER, d, "exc-done", fakeJpeg()).text("/result")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("A-03 반려 → 배송 중 유지 / 정상 판정 가능하면 예외 요청 불필요 / 기타 사유는 내용 필수")
    void rejectAndValidation() {
        String d = inTransitRemote();
        placeAtDistance(d, 0, 5.0, clock.instant());
        assertThat(post(RIDER, "/api/rider/deliveries/" + d + "/arrival-exceptions", Map.of("reasonCode", "GPS_ERROR")).text("/code"))
                .isEqualTo("ARRIVAL_EXCEPTION_NOT_NEEDED");

        mclock().advance(Duration.ofSeconds(60));
        placeAtDistance(d, 300, 5.0, clock.instant());
        assertThat(post(RIDER, "/api/rider/deliveries/" + d + "/arrival-exceptions", Map.of("reasonCode", "OTHER")).text("/code"))
                .isEqualTo("NOTE_REQUIRED");
        String id = post(RIDER, "/api/rider/deliveries/" + d + "/arrival-exceptions", Map.of("reasonCode", "COMPLEX_ENTRY"))
                .text("/exceptionId");
        Resp rej = post("ADMIN-TEST", "/api/admin/arrival-exceptions/" + id + "/reject", Map.of("note", "단지 진입 가능 확인, 정상 도착 처리 필요"));
        assertThat(rej.text("/status")).isEqualTo("REJECTED");
        assertThat(status(d)).isEqualTo("IN_TRANSIT");
        assertThat(post("ADMIN-TEST", "/api/admin/arrival-exceptions/" + id + "/approve", Map.of("note", "x")).text("/code"))
                .isEqualTo("ARRIVAL_EXCEPTION_CLOSED");
    }

    @Test
    @DisplayName("A-03 요청 후 라이더가 정상 도착 처리하면 승인 거부(OUTDATED), 요청은 무효(CANCELLED)로 기록 유지")
    void outdatedAfterNormalArrival() {
        String d = inTransitRemote();
        placeAtDistance(d, 480, 5.0, clock.instant());
        String id = post(RIDER, "/api/rider/deliveries/" + d + "/arrival-exceptions", Map.of("reasonCode", "GPS_ERROR")).text("/exceptionId");
        mclock().advance(Duration.ofMinutes(2));
        placeAtDistance(d, 30, 5.0, clock.instant());
        assertThat(arrive(RIDER, d).status()).isEqualTo(200);
        Resp r = post("ADMIN-TEST", "/api/admin/arrival-exceptions/" + id + "/approve", Map.of("note", "확인"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("/code")).isEqualTo("ARRIVAL_EXCEPTION_OUTDATED");
        assertThat(jdbcTemplate.queryForObject("select status from delivery_arrival_exception where id=?", String.class, id)).isEqualTo("CANCELLED");
        long arrivals = get("ADMIN-TEST", "/test-api/admin/deliveries/" + d).body().at("/history").valueStream()
                .filter(h -> "ARRIVED_AT_DESTINATION".equals(h.at("/toStatus").asString()) && "IN_TRANSIT".equals(h.at("/fromStatus").asString()))
                .count();
        assertThat(arrivals).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 4. 업무 상태·권한·멱등

    @Test
    @DisplayName("A-04 권한 없는 라이더 거부 / 배송 시작 전 거부 / 중복 도착 요청 멱등(이력 1건)")
    void stateAuthIdempotency() {
        String d = remoteDelivery("C-TEST-01", 1);
        pickUp(d, RIDER);
        Resp early = arrive(RIDER, d); // PICKED_UP (배송 출발 전)
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.text("/code")).isEqualTo("INVALID_DELIVERY_STATUS");
        assertThat(post(RIDER, "/api/rider/deliveries/" + d + "/start", null).status()).isEqualTo(200);
        placeAtDistance(d, 20, 5.0, clock.instant());

        Resp other = arrive("R-TEST-02", d);
        assertThat(other.status()).isEqualTo(403);
        assertThat(other.text("/code")).isEqualTo("NOT_ASSIGNED_RIDER");

        assertThat(arrive(RIDER, d).status()).isEqualTo(200);
        mclock().advance(Duration.ofMinutes(5)); // 위치가 오래돼도 이미 도착한 배송의 재요청은 멱등 성공
        Resp dup = arrive(RIDER, d);
        assertThat(dup.status()).isEqualTo(200);
        assertThat(dup.text("/status")).isEqualTo("ARRIVED_AT_DESTINATION");
        long arrivals = get("ADMIN-TEST", "/test-api/admin/deliveries/" + d).body().at("/history").valueStream()
                .filter(h -> "ARRIVED_AT_DESTINATION".equals(h.at("/toStatus").asString()) && "IN_TRANSIT".equals(h.at("/fromStatus").asString()))
                .count();
        assertThat(arrivals).isEqualTo(1);
        // 완료 후 도착 요청 → 상태 오류
        assertThat(complete(RIDER, d, "done", fakeJpeg()).status()).isEqualTo(200);
        assertThat(arrive(RIDER, d).text("/code")).isEqualTo("INVALID_DELIVERY_STATUS");
    }

    @Test
    @DisplayName("라이더 화면에 정책값과 서버 판정(거리·정확도·경과·사유)을 제공")
    void riderViewExposesCheck() {
        String d = inTransitRemote();
        placeAtDistance(d, 480, 5.0, clock.instant());
        var a = get(RIDER, "/api/rider/deliveries/" + d).body().at("/arrival");
        assertThat(a.at("/maxDistanceMeters").asDouble()).isEqualTo(150.0);
        assertThat(a.at("/maxLocationAgeSeconds").asInt()).isEqualTo(30);
        assertThat(a.at("/maxAccuracyMeters").asDouble()).isEqualTo(50.0);
        assertThat(a.at("/check/allowed").asBoolean()).isFalse();
        assertThat(a.at("/check/code").asString()).isEqualTo("TOO_FAR");
        assertThat(a.at("/check/distanceMeters").asDouble()).isCloseTo(480, offset(0.05));
    }
}
