package com.baropick.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.baropick.delivery.support.IntegrationTestBase;
import com.baropick.delivery.testsupport.simulation.SimulationService;

import tools.jackson.databind.JsonNode;

/**
 * 모드 A(SIMULATED_ROUTE) 서버 측 E2E: 실제 Delivery 배차·인계·위치 공통 처리·고객 조회를 관통한다.
 * 틱은 테스트가 직접 구동(가상 시계) — 실제 대기 없음. 결과는 FIXTURE 경로 기준이며 카카오 실호출이 아니다.
 */
class SimulationIT extends IntegrationTestBase {

    @Autowired SimulationService sim;
    @Autowired JdbcTemplate jdbc;

    private Resp create(String d, String rider, int rate, List<Map<String, Object>> faults) {
        Resp r = post("ADMIN-TEST", "/test-api/simulations", Map.of("deliveryId", d, "riderId", rider, "routeSource", "FIXTURE",
                "fixtureId", FIXTURE, "rate", rate, "seed", 7, "faults", faults));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r;
    }

    private String sid(Resp r) { return r.text("/scenarioId"); }

    private Resp cmd(String id, String c) { return post("ADMIN-TEST", "/test-api/simulations/" + id + "/" + c, null); }

    private String orderOf(String d) { return get("ADMIN-TEST", "/test-api/admin/deliveries/" + d).text("/delivery/orderId"); }

    @Test
    @DisplayName("D-25/D-21/D-30/D-34 생성→미리보기→시작→자동 재생→고객 조회 갱신(SIMULATED)→경로 끝에도 자동완료 없음→라이더 완료")
    void fullModeAFlow() {
        String d = remoteDelivery("C-TEST-01", 2);
        String id = sid(create(d, "R-TEST-01", 10, List.of()));
        assertThat(cmd(id, "prepare").text("/state")).isEqualTo("READY");
        JsonNode route = get("ADMIN-TEST", "/test-api/simulations/" + id + "/route").body();
        assertThat(route.path("source").asString()).isEqualTo("FIXTURE");
        assertThat(route.path("totalMeters").asDouble()).isBetween(2000.0, 4000.0);

        // D-29: 인계 전 START 거부
        Resp early = cmd(id, "start");
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.text("/code")).isEqualTo("RIDER_MISMATCH"); // 아직 배정 전
        assign(d, "R-TEST-01");
        assertThat(cmd(id, "start").text("/code")).isEqualTo("HANDOVER_NOT_COMPLETED");
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-mfc", null);
        seal(d);
        for (String qr : qrCodes(d)) post("R-TEST-01", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qr));
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/start", null);

        Resp started = cmd(id, "start");
        assertThat(started.text("/state")).isEqualTo("RUNNING");
        assertThat(cmd(id, "start").text("/code")).isEqualTo("ALREADY_RUNNING"); // D-28 중복 시작

        double prevLat = 0;
        int updates = 0;
        for (int i = 0; i < 200 && !sim.status(id).state().equals(SimulationService.State.FINISHED); i++) {
            sim.tick(id);
            JsonNode rider = get("C-TEST-01", "/api/customer/orders/" + orderOf(d) + "/tracking").at("/deliveries/0/rider");
            if (!rider.isNull() && rider.path("latitude").asDouble() != prevLat) {
                updates++;
                prevLat = rider.path("latitude").asDouble();
                assertThat(rider.path("source").asString()).isEqualTo("SIMULATED_ROUTE");
            }
        }
        var st = sim.status(id);
        assertThat(st.state()).isEqualTo(SimulationService.State.FINISHED);
        assertThat(updates).isGreaterThan(10);
        assertThat(st.counters().acceptedLatest()).isEqualTo(st.counters().sent());
        JsonNode tracking = get("C-TEST-01", "/api/customer/orders/" + orderOf(d) + "/tracking").at("/deliveries/0");
        assertThat(tracking.path("simulated").asBoolean()).isTrue();
        assertThat(tracking.path("rider").path("latitude").asDouble()).isCloseTo(T1_LAT, org.assertj.core.data.Offset.offset(0.0005));
        // D-30: 경로 끝 도달 ≠ 배송 완료
        assertThat(tracking.path("status").asString()).isEqualTo("IN_TRANSIT");
        assertThat(st.recentLog().stream().anyMatch(l -> l.detail().contains("배송 완료 아님"))).isTrue();
        // 라이더 앱 경로로만 완료
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-destination", null).status()).as("경로 끝=배송지, 150m 판정 통과").isEqualTo(200);
        assertThat(complete("R-TEST-01", d, "sim-done", fakeJpeg()).text("/result")).isEqualTo("COMPLETED");
        // 저장된 위치는 SIMULATED_ROUTE + scenarioId 로 분리
        Integer sims = jdbc.queryForObject("select count(*) from rider_location where delivery_id=? and source='SIMULATED_ROUTE' and scenario_id=?",
                Integer.class, d, id);
        Integer gps = jdbc.queryForObject("select count(*) from rider_location where delivery_id=? and source='DEVICE_GPS'", Integer.class, d);
        assertThat(sims).isGreaterThan(10);
        assertThat(gps).isZero();
    }

    private String pickedUpDelivery(String customer, String rider) {
        String d = remoteDelivery(customer, 1);
        pickUp(d, rider);
        post(rider, "/api/rider/deliveries/" + d + "/start", null);
        return d;
    }

    private int ticksToFinish(int rate) {
        String d = pickedUpDelivery("C-TEST-01", "R-TEST-0" + (rate == 1 ? 1 : rate == 5 ? 2 : 3));
        String id = sid(create(d, "R-TEST-0" + (rate == 1 ? 1 : rate == 5 ? 2 : 3), rate, List.of()));
        cmd(id, "prepare");
        cmd(id, "start");
        int n = 0;
        while (sim.status(id).state() == SimulationService.State.RUNNING && n < 5000) { sim.tick(id); n++; }
        var st = sim.status(id);
        assertThat(st.state()).isEqualTo(SimulationService.State.FINISHED);
        assertThat(st.progressedMeters()).isEqualTo(st.totalMeters());
        cmd(id, "stop");
        return n;
    }

    @Test
    @DisplayName("D-26 1×/5×/10× → 같은 경로·가상시간, 틱 수는 배속에 반비례")
    void d26_rates() {
        int t1 = ticksToFinish(1), t5 = ticksToFinish(5), t10 = ticksToFinish(10);
        assertThat(t1).isGreaterThan(t5).isGreaterThan(t10);
        assertThat((double) t1 / t10).isBetween(8.0, 12.0);
        assertThat((double) t1 / t5).isBetween(4.0, 6.0);
    }

    @Test
    @DisplayName("D-27/D-28 일시정지 중 송신 없음·재개 시 이어서 진행·잘못된 순서 명령 거부·종료→초기화")
    void d27_pauseResumeStopReset() {
        String d = pickedUpDelivery("C-TEST-01", "R-TEST-01");
        String id = sid(create(d, "R-TEST-01", 5, List.of()));
        assertThat(cmd(id, "pause").text("/code")).isEqualTo("INVALID_SIMULATION_STATE"); // CREATED 에서 pause
        assertThat(cmd(id, "start").text("/code")).isEqualTo("INVALID_SIMULATION_STATE"); // prepare 전 start
        cmd(id, "prepare");
        cmd(id, "start");
        for (int i = 0; i < 5; i++) sim.tick(id);
        var before = sim.status(id);
        assertThat(cmd(id, "pause").text("/state")).isEqualTo("PAUSED");
        assertThat(cmd(id, "pause").text("/code")).isEqualTo("ALREADY_PAUSED");
        long sent = before.counters().sent();
        for (int i = 0; i < 5; i++) sim.tick(id);
        assertThat(sim.status(id).counters().sent()).isEqualTo(sent);
        assertThat(sim.status(id).progressedMeters()).isEqualTo(before.progressedMeters());
        assertThat(put("ADMIN-TEST", "/test-api/simulations/" + id + "/rate", Map.of("rate", 3)).text("/code")).isEqualTo("INVALID_RATE");
        assertThat(put("ADMIN-TEST", "/test-api/simulations/" + id + "/rate", Map.of("rate", 10)).text("/rate")).isEqualTo("10");
        assertThat(cmd(id, "reset").text("/code")).isEqualTo("STOP_FIRST");
        cmd(id, "resume");
        sim.tick(id);
        assertThat(sim.status(id).progressedMeters()).isGreaterThan(before.progressedMeters());
        assertThat(cmd(id, "stop").text("/state")).isEqualTo("STOPPED");
        long sentAtStop = sim.status(id).counters().sent();
        sim.tick(id);
        assertThat(sim.status(id).counters().sent()).isEqualTo(sentAtStop);
        assertThat(cmd(id, "resume").status()).isEqualTo(409);
        Resp reset = cmd(id, "reset");
        assertThat(reset.text("/state")).isEqualTo("READY");
        assertThat(reset.at("/progressedMeters").asDouble()).isZero();
        assertThat(reset.at("/counters/sent").asLong()).isZero();
    }

    @Test
    @DisplayName("D-28 같은 배송에 재생기 중복 생성 차단")
    void d28_duplicateRun() {
        String d = pickedUpDelivery("C-TEST-01", "R-TEST-01");
        create(d, "R-TEST-01", 1, List.of());
        Resp dup = post("ADMIN-TEST", "/test-api/simulations", Map.of("deliveryId", d, "riderId", "R-TEST-01", "routeSource", "FIXTURE",
                "fixtureId", FIXTURE));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.text("/code")).isEqualTo("DUPLICATE_RUN");
    }

    @Test
    @DisplayName("D-29 재생 중 배송 취소 불가(픽업 후)·재배정 시뮬레이션: 완료되면 주입 중단")
    void d29_revokeOnCompletion() {
        String d = pickedUpDelivery("C-TEST-01", "R-TEST-01");
        String id = sid(create(d, "R-TEST-01", 1, List.of()));
        cmd(id, "prepare");
        cmd(id, "start");
        sim.tick(id);
        mclock().advance(java.time.Duration.ofMinutes(10)); // 라이더가 배송지까지 이동한 시간
        assertThat(arriveHere("R-TEST-01", d).status()).isEqualTo(200);
        complete("R-TEST-01", d, "early-done", fakeJpeg());
        var st = sim.status(id);
        assertThat(st.state()).isIn(SimulationService.State.STOPPED, SimulationService.State.FAILED);
        long sent = st.counters().sent();
        sim.tick(id);
        assertThat(sim.status(id).counters().sent()).isEqualTo(sent);
    }

    @Test
    @DisplayName("D-29 READY 상태 시나리오는 재배정 후 이전 라이더로 시작 불가")
    void d29_reassignBeforeStart() {
        String d = remoteDelivery("C-TEST-01", 1);
        assign(d, "R-TEST-01");
        String id = sid(create(d, "R-TEST-01", 1, List.of()));
        cmd(id, "prepare");
        post("ADMIN-TEST", "/test-api/admin/deliveries/" + d + "/reassign", Map.of("reason", "test"));
        Resp s = cmd(id, "start");
        assertThat(s.status()).isEqualTo(409);
        assertThat(s.text("/code")).isEqualTo("RIDER_MISMATCH");
    }

    @Test
    @DisplayName("D-20 DEVICE_GPS 배송에는 시뮬레이터 생성 불가")
    void d20_simOnGpsDelivery() {
        Resp o = createOrder("C-TEST-01", NEAR_MFC, 35.1810, 129.0790, 2000, 1);
        Resp r = post("ADMIN-TEST", "/test-api/simulations", Map.of("deliveryId", o.text("/deliveries/0/deliveryId"), "riderId", "R-TEST-01",
                "routeSource", "FIXTURE", "fixtureId", FIXTURE));
        assertThat(r.text("/code")).isEqualTo("NOT_SIMULATED_DELIVERY");
    }

    @Test
    @DisplayName("관리자 외 계정은 시뮬레이터 제어 불가")
    void adminOnly() {
        assertThat(get("C-TEST-01", "/test-api/simulations").status()).isEqualTo(403);
        assertThat(get("R-TEST-01", "/test-api/simulations").status()).isEqualTo(403);
        assertThat(get(null, "/test-api/simulations").status()).isEqualTo(401);
    }

    @Test
    @DisplayName("D-31 FIXTURE 끝점과 배송지 불일치 → 직선 대체 없이 차단 / KAKAO 키 없음 → 시나리오 FAILED")
    void d31_routeValidation() {
        Resp o = createOrder("C-TEST-01", REMOTE_MFC, 35.10, 129.03, 2000, 1);
        String d = o.text("/deliveries/0/deliveryId");
        String id = sid(create(d, "R-TEST-01", 1, List.of()));
        Resp p = cmd(id, "prepare");
        assertThat(p.text("/code")).isEqualTo("ROUTE_ENDPOINT_MISMATCH");
        assertThat(sim.status(id).state()).isEqualTo(SimulationService.State.FAILED);
        Resp k = post("ADMIN-TEST", "/test-api/simulations", Map.of("deliveryId", d, "riderId", "R-TEST-01", "routeSource", "KAKAO_DIRECTIONS"));
        String kid = sid(k);
        Resp kp = cmd(kid, "prepare");
        assertThat(kp.status()).isEqualTo(503);
        assertThat(kp.text("/code")).isEqualTo("KAKAO_KEY_MISSING");
    }

    @Test
    @DisplayName("D-32 장애 주입(정차·유실·지연·중복·역순·비정상·끊김) → 의도대로 재현, 최신 위치 역행 없음")
    void d32_faults() {
        String d = pickedUpDelivery("C-TEST-01", "R-TEST-01");
        List<Map<String, Object>> faults = List.of(
                Map.of("type", "STOP", "atFraction", 0.1, "durationSeconds", 30),
                Map.of("type", "DROP", "atFraction", 0.2, "durationTicks", 3),
                Map.of("type", "DELAY", "atFraction", 0.3, "durationTicks", 4),
                Map.of("type", "DUPLICATE", "atFraction", 0.45),
                Map.of("type", "REORDER", "atFraction", 0.55),
                Map.of("type", "ANOMALY", "atFraction", 0.65),
                Map.of("type", "DISCONNECT", "atFraction", 0.75, "durationTicks", 3));
        String id = sid(create(d, "R-TEST-01", 5, faults));
        cmd(id, "prepare");
        cmd(id, "start");
        double lastProgressLat = 0;
        String orderId = orderOf(d);
        boolean sawStop = false;
        int i = 0;
        while (sim.status(id).state() == SimulationService.State.RUNNING && i++ < 2000) {
            sim.tick(id);
            if (sim.status(id).stopped()) sawStop = true;
            JsonNode rider = get("C-TEST-01", "/api/customer/orders/" + orderId + "/tracking").at("/deliveries/0/rider");
            if (!rider.isNull()) {
                double lat = rider.path("latitude").asDouble();
                // 경로는 위도가 단조 감소 — 최신 위치 역행(위도 증가) 없음, 비정상 좌표 노출 없음
                if (lastProgressLat != 0) assertThat(lat).isLessThanOrEqualTo(lastProgressLat + 1e-9);
                assertThat(lat).isBetween(35.16, 35.17);
                lastProgressLat = lat;
            }
        }
        var st = sim.status(id);
        assertThat(st.state()).isEqualTo(SimulationService.State.FINISHED);
        assertThat(sawStop).isTrue();
        assertThat(st.counters().duplicate()).isEqualTo(1);
        assertThat(st.counters().history()).isGreaterThanOrEqualTo(1);   // REORDER
        assertThat(st.counters().rejected()).isEqualTo(2);              // ANOMALY: 범위 밖 + 순간이동
        assertThat(st.counters().generated()).isGreaterThan(st.counters().sent() - 4); // DROP/DISCONNECT 로 일부 미전송
        Integer anomalies = jdbc.queryForObject("select count(*) from rider_location where scenario_id=? and verdict in ('REJECTED_INVALID','REJECTED_ANOMALY')",
                Integer.class, id);
        assertThat(anomalies).isEqualTo(2);
    }

    @Test
    @DisplayName("A-05 시뮬레이션도 150m 판정: 경로 중간 거부 → 경로 끝 정차 위치로 30초 최신성 유지 → 허용 / 완료 후 정차 전송 중단")
    void arrivalPolicyInSimulation() {
        String d = pickedUpDelivery("C-TEST-01", "R-TEST-01");
        String id = sid(create(d, "R-TEST-01", 10, List.of()));
        cmd(id, "prepare");
        cmd(id, "start");
        sim.tick(id);
        Resp mid = post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-destination", null);
        assertThat(mid.status()).isEqualTo(409);
        assertThat(mid.text("/code")).isEqualTo("ARRIVAL_TOO_FAR");

        for (int i = 0; i < 500 && sim.status(id).state() == SimulationService.State.RUNNING; i++) sim.tick(id);
        var fin = sim.status(id);
        assertThat(fin.state()).isEqualTo(SimulationService.State.FINISHED);
        assertThat(fin.dwelling()).isTrue();

        // 경로 끝 도달 후 31초 동안 위치가 없으면(정차 전송 없음) 재측정 요청
        mclock().advance(java.time.Duration.ofSeconds(31));
        Resp stale = post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-destination", null);
        assertThat(stale.status()).isEqualTo(422);
        assertThat(stale.text("/details/reason")).isEqualTo("LOCATION_STALE");

        // 정차 위치 1건(같은 수집·검증 경로) → 최신 위치 → 도착 허용
        var dw = sim.dwellTick(id);
        assertThat(dw.counters().dwellSent()).isEqualTo(1);
        Resp ok = post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-destination", null);
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);

        // 완료 후에는 위치 수신이 거부되어 정차 전송이 스스로 중단
        assertThat(complete("R-TEST-01", d, "dwell-done", fakeJpeg()).text("/result")).isEqualTo("COMPLETED");
        var after = sim.dwellTick(id);
        assertThat(after.dwelling()).isFalse();
        assertThat(after.counters().dwellSent()).isEqualTo(1);
        assertThat(after.recentLog().stream().anyMatch(l -> l.type().equals("DWELL_END"))).isTrue();
    }
}
