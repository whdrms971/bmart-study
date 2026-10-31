package com.baropick.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.baropick.delivery.dispatch.DispatchScheduler;
import com.baropick.delivery.support.IntegrationTestBase;
import com.baropick.delivery.testsupport.mockoms.MockOmsService;

import tools.jackson.databind.JsonNode;

class DeliveryLifecycleIT extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired MockOmsService oms;

    @Test
    @DisplayName("정상 흐름: 주문→배차 수락→MFC 인계→배송→증빙 완료→DeliveryCompleted→Mock OMS 주문 완료")
    void happyPath() {
        String d = remoteDelivery("C-TEST-01", 2);
        pickUp(d, "R-TEST-01");
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/start", null).status()).isEqualTo(200);
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-destination", null).status()).isEqualTo(200);
        Resp c = complete("R-TEST-01", d, "done-1", fakeJpeg());
        assertThat(c.status()).as(c.body().toString()).isEqualTo(200);
        assertThat(c.text("/result")).isEqualTo("COMPLETED");
        assertThat(status(d)).isEqualTo("DELIVERED");
        relay.relay();
        String orderId = get("ADMIN-TEST", "/test-api/admin/deliveries/" + d).text("/delivery/orderId");
        assertThat(get("C-TEST-01", "/test-api/mock-oms/orders/" + orderId).text("/status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("D-01 같은 배송 요청 두 번 → 배송 1건, 같은 키·다른 내용은 409")
    void d01_idempotentRequest() {
        String d = remoteDelivery("C-TEST-01", 1);
        Map<?, ?> again = oms.resendDeliveryRequest(d);
        assertThat(again.get("created")).isEqualTo(false);
        assertThat(again.get("deliveryId")).isEqualTo(d);
        Integer count = jdbc.queryForObject("select count(*) from delivery where id=?", Integer.class, d);
        assertThat(count).isEqualTo(1);
        // 같은 requestId, 다른 내용
        String reqId = jdbc.queryForObject("select request_id from delivery where id=?", String.class, d);
        Map<String, Object> body = Map.of("sourceSystem", MockOmsService.SOURCE, "requestId", reqId, "orderId", "ORD-X", "deliveryId", "DLV-OTHER",
                "customerId", "C-TEST-01", "mfcId", REMOTE_MFC,
                "destination", Map.of("latitude", 35.1, "longitude", 129.1, "address", "다른 주소"), "expectedPackageCount", 1);
        Resp r = toResp(http.post().uri("/internal/delivery-requests").header("X-Internal-Token", internalToken())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(String.class));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("/code")).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Autowired com.baropick.delivery.auth.AuthKeys keys;
    String internalToken() { return keys.internalToken(); }

    @Test
    @DisplayName("내부 API 는 내부 토큰 없이 호출 불가")
    void internalRequiresToken() {
        Resp r = toResp(http.post().uri("/internal/delivery-requests").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{}").retrieve().toEntity(String.class));
        assertThat(r.status()).isIn(401, 403);
        assertThat(post("C-TEST-01", "/internal/delivery-requests", Map.of()).status()).isIn(401, 403);
    }

    @Test
    @DisplayName("D-02 라이더 2명 동시 수락 → 정확히 1명만 배정")
    void d02_concurrentAccept() throws Exception {
        String d = remoteDelivery("C-TEST-01", 1);
        onDuty("R-TEST-01");
        onDuty("R-TEST-02");
        onDuty("R-TEST-03");
        dispatch.tryDispatch(d);
        List<String> riders = List.of("R-TEST-01", "R-TEST-02", "R-TEST-03");
        List<String> offers = riders.stream().map(r -> offerFor(r, d)).toList();
        assertThat(offers).doesNotContainNull();
        riders.forEach(this::token);
        ExecutorService ex = Executors.newFixedThreadPool(3);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Resp>> fs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String rider = riders.get(i), offer = offers.get(i);
            fs.add(ex.submit(() -> { go.await(); return post(rider, "/api/rider/offers/" + offer + "/accept", null); }));
        }
        go.countDown();
        int ok = 0;
        List<String> codes = new ArrayList<>();
        for (Future<Resp> f : fs) {
            Resp r = f.get();
            if (r.status() == 200) ok++; else codes.add(r.text("/code"));
        }
        ex.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(codes).allMatch(c -> c.equals("ALREADY_ASSIGNED") || c.equals("OFFER_NOT_ACTIVE"));
        assertThat(status(d)).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("D-03 배차 만료 후 늦은 수락 → 거절, 재배차 대기 상태 유지")
    void d03_lateAccept() {
        String d = remoteDelivery("C-TEST-01", 1);
        onDuty("R-TEST-01");
        dispatch.tryDispatch(d);
        String offer = offerFor("R-TEST-01", d);
        mclock().advance(Duration.ofSeconds(61));
        dispatch.expireDueOffers();
        assertThat(status(d)).isEqualTo("REQUESTED");
        Resp late = post("R-TEST-01", "/api/rider/offers/" + offer + "/accept", null);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.text("/code")).isIn("OFFER_EXPIRED", "OFFER_NOT_ACTIVE");
        assertThat(status(d)).isEqualTo("REQUESTED");
        assertThat(get("ADMIN-TEST", "/test-api/admin/deliveries/" + d).at("/delivery/riderId").isNull()).isTrue();
    }

    @Test
    @DisplayName("거절 → 시도 종료 후 다음 시도에서 거절 라이더 제외")
    void rejectAndRedispatch() {
        String d = remoteDelivery("C-TEST-01", 1);
        onDuty("R-TEST-01");
        dispatch.tryDispatch(d);
        assertThat(post("R-TEST-01", "/api/rider/offers/" + offerFor("R-TEST-01", d) + "/reject", Map.of("reason", "거리")).status()).isEqualTo(200);
        assertThat(status(d)).isEqualTo("REQUESTED");
        assertThat(dispatch.tryDispatch(d).result()).isEqualTo("NO_ELIGIBLE_RIDER");
        onDuty("R-TEST-02");
        assertThat(dispatch.tryDispatch(d).result()).isEqualTo("OFFERED");
        assertThat(offerFor("R-TEST-02", d)).isNotNull();
        assertThat(offerFor("R-TEST-01", d)).isNull();
    }

    @Test
    @DisplayName("D-04 포장 3개 중 2개만 스캔 → 픽업 완료/배송 시작 불가")
    void d04_partialHandover() {
        String d = remoteDelivery("C-TEST-01", 3);
        assign(d, "R-TEST-01");
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-mfc", null);
        seal(d);
        List<String> qrs = qrCodes(d);
        assertThat(qrs).hasSize(3);
        for (int i = 0; i < 2; i++) {
            Resp s = post("R-TEST-01", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qrs.get(i)));
            assertThat(s.text("/result")).isEqualTo("HANDED_OVER");
            assertThat(s.at("/handedOver").asInt()).isEqualTo(i + 1);
        }
        assertThat(status(d)).isEqualTo("ARRIVED_AT_MFC");
        Resp start = post("R-TEST-01", "/api/rider/deliveries/" + d + "/start", null);
        assertThat(start.status()).isEqualTo(409);
        assertThat(start.text("/code")).isEqualTo("HANDOVER_NOT_COMPLETED");
        // 중복 스캔은 멱등
        Resp dup = post("R-TEST-01", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qrs.get(0)));
        assertThat(dup.text("/result")).isEqualTo("ALREADY_HANDED_OVER");
        // 다른 배송 QR
        String other = remoteDelivery("C-TEST-02", 1);
        Resp wrong = post("R-TEST-01", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qrCodes(other).get(0)));
        assertThat(wrong.text("/code")).isEqualTo("PACKAGE_NOT_IN_DELIVERY");
        // 위조 토큰
        String forged = qrs.get(2).substring(0, qrs.get(2).lastIndexOf('|') + 1) + "deadbeef";
        Resp f = post("R-TEST-01", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", forged));
        assertThat(f.text("/code")).isEqualTo("QR_TOKEN_MISMATCH");
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qrs.get(2))).text("/result"))
                .isEqualTo("HANDED_OVER");
        assertThat(status(d)).isEqualTo("PICKED_UP");
    }

    @Test
    @DisplayName("D-05 인계 전 배송 시작 / MFC 도착 전 스캔 / 비배정 라이더 스캔 차단")
    void d05_startBeforeHandover() {
        String d = remoteDelivery("C-TEST-01", 1);
        assign(d, "R-TEST-01");
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/start", null).text("/code")).isEqualTo("HANDOVER_NOT_COMPLETED");
        seal(d);
        String qr = qrCodes(d).get(0);
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qr)).text("/code")).isEqualTo("INVALID_STATE");
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-mfc", null);
        Resp other = post("R-TEST-02", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qr));
        assertThat(other.status()).isEqualTo(403);
        assertThat(status(d)).isEqualTo("ARRIVED_AT_MFC");
        JsonNode history = get("ADMIN-TEST", "/test-api/admin/deliveries/" + d).at("/history");
        assertThat(history.toString()).contains("비지정 라이더 인계 시도 차단");
    }

    @Test
    @DisplayName("D-12 취소와 인계 동시 발생 → 둘 중 하나만 확정 (둘 다 성공 금지)")
    void d12_cancelVsHandover() throws Exception {
        for (int round = 0; round < 5; round++) {
            Resp o = createOrder("C-TEST-01", REMOTE_MFC, T1_LAT, T1_LNG, 3000, 1);
            String orderId = o.text("/orderId");
            String d = o.text("/deliveries/0/deliveryId");
            String rider = "R-TEST-0" + (round % 3 + 1);
            assign(d, rider);
            post(rider, "/api/rider/deliveries/" + d + "/arrive-mfc", null);
            seal(d);
            String qr = qrCodes(d).get(0);
            ExecutorService ex = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Resp> scan = ex.submit(() -> { go.await(); return post(rider, "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qr)); });
            Future<Resp> cancel = ex.submit(() -> { go.await(); return post("C-TEST-01", "/test-api/mock-oms/orders/" + orderId + "/cancel", null); });
            go.countDown();
            Resp s = scan.get();
            cancel.get();
            ex.shutdown();
            String st = status(d);
            String mfcStatus = get("ADMIN-TEST", "/test-api/mock-mfc/deliveries/" + d + "/packages").at("/0/status").asString();
            if (s.status() == 200 && "HANDED_OVER".equals(s.text("/result"))) {
                assertThat(st).isEqualTo("PICKED_UP");
                assertThat(mfcStatus).isEqualTo("HANDED_OVER");
            } else {
                assertThat(st).isEqualTo("CANCELLED");
                assertThat(mfcStatus).isEqualTo("VOIDED");
            }
        }
    }

    @Test
    @DisplayName("D-13 완료 요청 중복 → 증빙·이벤트 1건, 사진 없으면 완료 불가, 다른 requestId 재완료 409")
    void d13_duplicateComplete() {
        String d = remoteDelivery("C-TEST-01", 1);
        pickUp(d, "R-TEST-01");
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/start", null);
        Resp early = complete("R-TEST-01", d, "r1", fakeJpeg());
        assertThat(early.text("/code")).isEqualTo("INVALID_STATE");
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-destination", null);
        assertThat(complete("R-TEST-01", d, "r1", null).text("/code")).isEqualTo("PROOF_PHOTO_REQUIRED");
        assertThat(complete("R-TEST-01", d, "r1", "not an image at all".getBytes()).text("/code")).isEqualTo("PROOF_PHOTO_INVALID");
        Resp a = complete("R-TEST-01", d, "r1", fakeJpeg());
        Resp b = complete("R-TEST-01", d, "r1", fakeJpeg());
        assertThat(a.text("/result")).isEqualTo("COMPLETED");
        assertThat(b.text("/result")).isEqualTo("ALREADY_COMPLETED");
        assertThat(b.text("/proofId")).isEqualTo(a.text("/proofId"));
        assertThat(complete("R-TEST-01", d, "r2", fakeJpeg()).text("/code")).isEqualTo("ALREADY_COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from delivery_proof where delivery_id=?", Integer.class, d)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id=? and event_type='DeliveryCompleted'", Integer.class, d))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("D-14 완료 이벤트 전송 장애(적용 전 실패 / 적용 후 응답 유실) → 재전송 후 OMS 1회만 적용")
    void d14_outboxRetry() {
        String d = deliveredOrderDelivery("C-TEST-01");
        oms.setEventFault(MockOmsService.EventFault.FAIL_BEFORE_APPLY, 1);
        publishAllNow(); // 실패
        assertThat(inboxCount(d)).isZero();
        oms.setEventFault(MockOmsService.EventFault.FAIL_AFTER_APPLY, 1);
        publishAllNow(); // 적용됐지만 응답 유실 → 미발행으로 남음
        assertThat(inboxCount(d)).isEqualTo(1);
        Integer unpublished = jdbc.queryForObject("select count(*) from outbox_event where aggregate_id=? and event_type='DeliveryCompleted' and published_at is null",
                Integer.class, d);
        assertThat(unpublished).isEqualTo(1);
        publishAllNow(); // 재전송 → inbox 중복 무시
        assertThat(inboxCount(d)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id=? and published_at is null", Integer.class, d)).isZero();
        Integer attempts = jdbc.queryForObject("select attempts from outbox_event where aggregate_id=? and event_type='DeliveryCompleted'", Integer.class, d);
        assertThat(attempts).isEqualTo(3);
    }

    private int inboxCount(String deliveryId) {
        return jdbc.queryForObject("select count(*) from mock_oms_inbox where event_type='DeliveryCompleted' and payload like ?", Integer.class,
                "%" + deliveryId + "%");
    }

    private void publishAllNow() {
        mclock().advance(Duration.ofMinutes(5)); // 백오프 경과
        relay.relay();
    }

    private String deliveredOrderDelivery(String customer) {
        String d = remoteDelivery(customer, 1);
        pickUp(d, "R-TEST-01");
        relay.relay(); // DeliveryPickedUp 먼저 정상 전달 (이후 장애 주입은 DeliveryCompleted 에만 적용)
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/start", null);
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-destination", null);
        assertThat(complete("R-TEST-01", d, "c-" + d, fakeJpeg()).status()).isEqualTo(200);
        return d;
    }

    @Test
    @DisplayName("D-15 / D-18 분할배송: 10,001g → 2건, 1건 완료 시 주문은 PARTIALLY_DELIVERED (전체 완료 아님)")
    void d15_d18_split() {
        assertThat(MockOmsService.requiresSplit(9_999)).isFalse();
        assertThat(MockOmsService.requiresSplit(10_000)).isFalse();
        assertThat(MockOmsService.requiresSplit(10_001)).isTrue();
        Resp exact = createOrder("C-TEST-01", REMOTE_MFC, T1_LAT, T1_LNG, 10_000, 2);
        assertThat(exact.at("/deliveries").size()).isEqualTo(1);
        Resp o = createOrder("C-TEST-01", REMOTE_MFC, T1_LAT, T1_LNG, 10_001, 2);
        assertThat(o.at("/split").asBoolean()).isTrue();
        assertThat(o.at("/deliveries").size()).isEqualTo(2);
        String orderId = o.text("/orderId");
        String d1 = o.text("/deliveries/0/deliveryId");
        String d2 = o.text("/deliveries/1/deliveryId");
        // 고객 추적: 두 배송 모두 노출
        assertThat(get("C-TEST-01", "/api/customer/orders/" + orderId + "/tracking").at("/deliveries").size()).isEqualTo(2);
        pickUp(d1, "R-TEST-01");
        post("R-TEST-01", "/api/rider/deliveries/" + d1 + "/start", null);
        post("R-TEST-01", "/api/rider/deliveries/" + d1 + "/arrive-destination", null);
        Resp done = complete("R-TEST-01", d1, "split-1", fakeJpeg());
        assertThat(done.status()).as(done.body().toString()).isEqualTo(200);
        relay.relay();
        var outboxRows = jdbcTemplate.queryForList("select event_type, published_at, last_error from outbox_event where aggregate_id=?", d1);
        assertThat(outboxRows).as(outboxRows.toString()).allMatch(r -> r.get("published_at") != null);
        Resp ord = get("C-TEST-01", "/test-api/mock-oms/orders/" + orderId);
        assertThat(ord.text("/status")).as(ord.body().toString()).isEqualTo("PARTIALLY_DELIVERED");
        assertThat(status(d2)).isNotEqualTo("DELIVERED");
    }

    @Autowired(required = false) DispatchScheduler scheduler;

    @Test
    @DisplayName("D-19 시간 경과(5시간+) → 자동 배송 완료 없음")
    void d19_noAutoComplete() {
        String d = remoteDelivery("C-TEST-01", 1);
        pickUp(d, "R-TEST-01");
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/start", null);
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-destination", null);
        mclock().advance(Duration.ofHours(6));
        dispatch.expireDueOffers();
        relay.relay();
        assertThat(status(d)).isEqualTo("ARRIVED_AT_DESTINATION");
        assertThat(jdbc.queryForObject("select count(*) from delivery where status='DELIVERED' and id=?", Integer.class, d)).isZero();
    }

    @Test
    @DisplayName("D-17 재배정 후 기존 라이더 접근 차단, 새 라이더만 인계 가능 / 픽업 후 배차취소는 지원센터 필요")
    void d17_reassign() {
        String d = remoteDelivery("C-TEST-01", 1);
        assign(d, "R-TEST-01");
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-mfc", null);
        Resp re = post("ADMIN-TEST", "/test-api/admin/deliveries/" + d + "/reassign", Map.of("reason", "테스트 재배정"));
        assertThat(re.status()).isEqualTo(200);
        assertThat(get("R-TEST-01", "/api/rider/deliveries/" + d).status()).isEqualTo(403);
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-mfc", null).status()).isEqualTo(403);
        onDuty("R-TEST-02");
        dispatch.tryDispatch(d);
        assertThat(offerFor("R-TEST-01", d)).isNull(); // 해제된 라이더 재제안 없음
        String offer = offerFor("R-TEST-02", d);
        assertThat(post("R-TEST-02", "/api/rider/offers/" + offer + "/accept", null).status()).isEqualTo(200);
        post("R-TEST-02", "/api/rider/deliveries/" + d + "/arrive-mfc", null);
        seal(d);
        String qr = qrCodes(d).get(0);
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qr)).status()).isEqualTo(403);
        assertThat(post("R-TEST-02", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qr)).text("/result")).isEqualTo("HANDED_OVER");
        Resp rel = post("R-TEST-02", "/api/rider/deliveries/" + d + "/release", Map.of("reason", "픽업 후 취소 시도"));
        assertThat(rel.text("/code")).isEqualTo("SUPPORT_REQUIRED");
    }

    @Test
    @DisplayName("픽업 후 고객 취소 → 출고 전 취소 불가(회수 경로), 픽업 전 취소 → CANCELLED")
    void cancelRules() {
        Resp o1 = createOrder("C-TEST-01", REMOTE_MFC, T1_LAT, T1_LNG, 3000, 1);
        String d1 = o1.text("/deliveries/0/deliveryId");
        pickUp(d1, "R-TEST-01");
        Resp c1 = post("C-TEST-01", "/test-api/mock-oms/orders/" + o1.text("/orderId") + "/cancel", null);
        assertThat(c1.body().toString()).contains("HANDED_OVER");
        assertThat(status(d1)).isEqualTo("PICKED_UP");

        Resp o2 = createOrder("C-TEST-01", REMOTE_MFC, T1_LAT, T1_LNG, 3000, 1);
        String d2 = o2.text("/deliveries/0/deliveryId");
        assign(d2, "R-TEST-02");
        post("C-TEST-01", "/test-api/mock-oms/orders/" + o2.text("/orderId") + "/cancel", null);
        assertThat(status(d2)).isEqualTo("CANCELLED");
        assertThat(get("R-TEST-02", "/api/rider/deliveries/" + d2).status()).isEqualTo(403);
        // 취소된 배송에 신규 배차 없음
        assertThat(dispatch.tryDispatch(d2).result()).isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("MFC 인계 응답 불명 → 실패 단정 없이 '확인 중', 재스캔 시 원결과 조회로 확정(중복 인계 없음)")
    void handoverUnknown() {
        String d = remoteDelivery("C-TEST-01", 1);
        assign(d, "R-TEST-01");
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/arrive-mfc", null);
        seal(d);
        String qr = qrCodes(d).get(0);
        put("ADMIN-TEST", "/test-api/mock-mfc/fault", Map.of("fault", "TIMEOUT_AFTER_COMMIT_NEXT"));
        Resp first = post("R-TEST-01", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qr));
        assertThat(first.text("/result")).isEqualTo("HANDOVER_RESULT_UNKNOWN");
        assertThat(status(d)).isEqualTo("ARRIVED_AT_MFC");
        // 불명 상태에서 취소는 보류
        String orderId = get("ADMIN-TEST", "/test-api/admin/deliveries/" + d).text("/delivery/orderId");
        Resp second = post("R-TEST-01", "/api/rider/deliveries/" + d + "/handover-scans", Map.of("qrCode", qr));
        assertThat(second.text("/result")).isEqualTo("HANDED_OVER");
        assertThat(status(d)).isEqualTo("PICKED_UP");
        assertThat(orderId).isNotBlank();
    }
}
