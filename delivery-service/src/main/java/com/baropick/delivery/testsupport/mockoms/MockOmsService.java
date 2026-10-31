package com.baropick.delivery.testsupport.mockoms;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Geo;
import com.baropick.delivery.common.Ids;
import com.baropick.delivery.intake.DeliveryRequestService;
import com.baropick.delivery.mfc.MfcRegistry;
import com.baropick.delivery.testsupport.LocalServerClient;
import com.baropick.delivery.testsupport.mockmfc.MockMfcService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * [Mock] OMS. 테스트 주문 생성 → (10,000g 초과 시 분할) → Delivery 배송 요청(HTTP 내부 계약) → Mock MFC 이행 요청.
 * Delivery 이벤트는 (sourceSystem, eventId) inbox 로 1회만 적용하고, 주문 완료는 "모든 배송 완료"일 때만 판정한다 (D-15).
 * 결제·재고 예약·클레임은 구현하지 않는다.
 */
@Service
@Profile("local-test")
public class MockOmsService {
    private static final Logger log = LoggerFactory.getLogger(MockOmsService.class);
    public static final String SOURCE = "OMS-MOCK";
    /** [확정 정책] 총 상품중량 10,000g "초과" 시 분할. 정확히 10,000g 은 미분할 (DECISIONS C-02). */
    public static final int SPLIT_THRESHOLD_GRAMS = 10_000;

    public enum EventFault { NONE, FAIL_BEFORE_APPLY, FAIL_AFTER_APPLY }

    public record TestOrderRequest(String mfcId, Double latitude, Double longitude, String address, String addressDetail,
                                   Integer totalWeightGrams, Integer packageCount, String customerNote, Boolean autoSeal) {}

    public record OrderDeliveryView(String deliveryId, String requestId, int packageCount, int weightGrams, String status, Instant completedAt) {}

    public record OrderView(String orderId, String customerId, String mfcId, String status, int totalWeightGrams, String address,
                            String addressDetail, double latitude, double longitude, boolean split, Instant createdAt, Instant completedAt,
                            List<OrderDeliveryView> deliveries) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LocalServerClient server;
    private final MockMfcService mfc;
    private final MfcRegistry mfcs;
    private final JsonMapper json;
    private final Clock clock;
    private volatile EventFault eventFault = EventFault.NONE;
    private final AtomicInteger faultRemaining = new AtomicInteger();

    public MockOmsService(JdbcTemplate jdbc, TransactionTemplate tx, LocalServerClient server, MockMfcService mfc, MfcRegistry mfcs,
                          JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.server = server;
        this.mfc = mfc;
        this.mfcs = mfcs;
        this.json = json;
        this.clock = clock;
    }

    public static boolean requiresSplit(int totalWeightGrams) { return totalWeightGrams > SPLIT_THRESHOLD_GRAMS; }

    public OrderView createTestOrder(String customerId, TestOrderRequest req) {
        if (req.latitude() == null || req.longitude() == null || !Geo.validLat(req.latitude()) || !Geo.validLng(req.longitude())) {
            throw ApiException.badRequest("INVALID_COORDINATE", "배송지 좌표 오류");
        }
        if (req.address() == null || req.address().isBlank()) throw ApiException.badRequest("ADDRESS_REQUIRED", "배송지 주소가 필요합니다");
        mfcs.require(req.mfcId());
        int weight = req.totalWeightGrams() == null ? 3000 : req.totalWeightGrams();
        int pkgs = req.packageCount() == null ? 1 : req.packageCount();
        if (weight <= 0 || pkgs < 1 || pkgs > 10) throw ApiException.badRequest("INVALID_ORDER", "중량/포장 수 오류");
        boolean split = requiresSplit(weight);
        String orderId = Ids.next("ORD");
        Instant now = clock.instant();
        // 분할 세부 규칙은 [결정 필요] — Mock 은 2건으로 나누고 무게·포장을 균등 분배한다(테스트 전용)
        List<int[]> parts = new ArrayList<>();
        if (split) {
            int p1 = Math.max(1, pkgs / 2), p2 = Math.max(1, pkgs - p1);
            parts.add(new int[] {weight / 2, p1});
            parts.add(new int[] {weight - weight / 2, p2});
        } else {
            parts.add(new int[] {weight, pkgs});
        }
        tx.executeWithoutResult(s -> jdbc.update(
                "insert into mock_oms_order(order_id, customer_id, mfc_id, status, total_weight_grams, address, address_detail, lat, lng, split, created_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                orderId, customerId, req.mfcId(), "CONFIRMED", weight, req.address(), req.addressDetail(), req.latitude(), req.longitude(), split,
                Timestamp.from(now)));
        int i = 0;
        for (int[] part : parts) {
            i++;
            String deliveryId = DeliveryRequestService.newDeliveryId();
            String requestId = orderId + "-D" + i;
            jdbc.update("insert into mock_oms_order_delivery(delivery_id, order_id, request_id, package_count, weight_grams, status) values (?,?,?,?,?,?)",
                    deliveryId, orderId, requestId, part[1], part[0], "REQUESTED");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sourceSystem", SOURCE);
            body.put("requestId", requestId);
            body.put("orderId", orderId);
            body.put("deliveryId", deliveryId);
            body.put("customerId", customerId);
            body.put("mfcId", req.mfcId());
            body.put("destination", Map.of("latitude", req.latitude(), "longitude", req.longitude(), "address", req.address(),
                    "addressDetail", req.addressDetail() == null ? "" : req.addressDetail()));
            body.put("expectedPackageCount", part[1]);
            body.put("totalWeightGrams", part[0]);
            body.put("customerNote", req.customerNote());
            sendDeliveryRequest(body);
            mfc.createPackages(deliveryId, orderId, req.mfcId(), part[1]);
            if (Boolean.TRUE.equals(req.autoSeal())) mfc.seal(deliveryId);
        }
        return order(orderId);
    }

    /** 같은 요청을 재전송해도 Delivery 는 하나만 만든다 (D-01). 테스트·관리자 재전송 버튼에서도 사용. */
    public Map<?, ?> sendDeliveryRequest(Map<String, Object> body) {
        return server.post("/internal/delivery-requests").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    }

    public Map<?, ?> resendDeliveryRequest(String deliveryId) {
        var rows = jdbc.queryForList("select d.*, o.customer_id, o.mfc_id, o.lat, o.lng, o.address, o.address_detail from mock_oms_order_delivery d join mock_oms_order o on o.order_id=d.order_id where d.delivery_id=?", deliveryId);
        if (rows.isEmpty()) throw ApiException.notFound("NOT_FOUND", deliveryId);
        var r = rows.get(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceSystem", SOURCE);
        body.put("requestId", r.get("request_id"));
        body.put("orderId", r.get("order_id"));
        body.put("deliveryId", deliveryId);
        body.put("customerId", r.get("customer_id"));
        body.put("mfcId", r.get("mfc_id"));
        body.put("destination", Map.of("latitude", r.get("lat"), "longitude", r.get("lng"), "address", r.get("address"),
                "addressDetail", r.get("address_detail") == null ? "" : r.get("address_detail")));
        body.put("expectedPackageCount", r.get("package_count"));
        body.put("totalWeightGrams", r.get("weight_grams"));
        body.put("customerNote", null);
        return sendDeliveryRequest(body);
    }

    /** 주문 취소: 배송별 MFC 출고 중지 → 전 포장 STOPPED 인 배송만 Delivery 취소 요청 (cancel-vs-handover S3). */
    public Map<String, Object> cancelOrder(String orderId, String customerIdOrNull) {
        OrderView o = order(orderId);
        if (customerIdOrNull != null && !o.customerId().equals(customerIdOrNull)) throw ApiException.notFound("ORDER_NOT_FOUND", "주문 없음");
        Map<String, Object> result = new LinkedHashMap<>();
        for (OrderDeliveryView d : o.deliveries()) {
            MockMfcService.StopResult stop = mfc.stop(d.deliveryId());
            if (!stop.allStopped()) {
                result.put(d.deliveryId(), Map.of("mfcStop", stop.perPackage(), "delivery", "NOT_REQUESTED — 인계된 포장 존재, 회수 경로"));
                continue;
            }
            try {
                Map<?, ?> r = server.post("/internal/deliveries/" + d.deliveryId() + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("sourceSystem", SOURCE, "requestId", "CXL-" + d.deliveryId(), "reason", "고객 주문 취소(테스트)"))
                        .retrieve().body(Map.class);
                result.put(d.deliveryId(), Map.of("mfcStop", stop.perPackage(), "delivery", r));
            } catch (HttpClientErrorException e) {
                result.put(d.deliveryId(), Map.of("mfcStop", stop.perPackage(), "delivery", "ERROR " + e.getStatusCode().value() + " " + e.getResponseBodyAsString()));
            }
        }
        return result;
    }

    public void setEventFault(EventFault f, int times) { this.eventFault = f; this.faultRemaining.set(times); }
    public EventFault eventFault() { return faultRemaining.get() > 0 ? eventFault : EventFault.NONE; }

    /** Delivery 이벤트 수신. (sourceSystem, eventId) inbox 로 1회만 적용. */
    public String onEvent(String rawJson) {
        JsonNode ev = json.readTree(rawJson);
        String source = ev.path("sourceSystem").asString("");
        String eventId = ev.path("eventId").asString("");
        String type = ev.path("eventType").asString("");
        if (source.isBlank() || eventId.isBlank()) throw ApiException.badRequest("INVALID_EVENT", "sourceSystem/eventId 필요");
        boolean injectBefore = consumeFault(EventFault.FAIL_BEFORE_APPLY);
        if (injectBefore) throw ApiException.unavailable("OMS_UNAVAILABLE", "[장애 주입] OMS 일시 장애 (적용 전)");
        String outcome = tx.execute(s -> {
            try {
                jdbc.update("insert into mock_oms_inbox(source_system, event_id, event_type, payload, applied_at) values (?,?,?,?,?)",
                        source, eventId, type, rawJson.length() > 3900 ? rawJson.substring(0, 3900) : rawJson, Timestamp.from(clock.instant()));
            } catch (DuplicateKeyException dup) {
                return "DUPLICATE_IGNORED";
            }
            apply(type, ev);
            return "APPLIED";
        });
        if (consumeFault(EventFault.FAIL_AFTER_APPLY)) {
            throw ApiException.unavailable("OMS_RESPONSE_LOST", "[장애 주입] 적용 후 응답 유실");
        }
        log.info("Mock OMS 이벤트 {} {} → {}", type, eventId, outcome);
        return outcome;
    }

    private boolean consumeFault(EventFault f) {
        if (eventFault != f) return false;
        return faultRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    private void apply(String type, JsonNode ev) {
        String deliveryId = ev.path("deliveryId").asString("");
        String orderId = ev.path("orderId").asString("");
        Instant now = clock.instant();
        switch (type) {
            case "DeliveryPickedUp" -> jdbc.update("update mock_oms_order_delivery set status='PICKED_UP' where delivery_id=? and status in ('REQUESTED')", deliveryId);
            case "DeliveryCompleted" -> {
                jdbc.update("update mock_oms_order_delivery set status='DELIVERED', completed_at=? where delivery_id=?", Timestamp.from(now), deliveryId);
                recomputeOrder(orderId, now);
            }
            case "DeliveryCancelled" -> {
                jdbc.update("update mock_oms_order_delivery set status='CANCELLED' where delivery_id=?", deliveryId);
                recomputeOrder(orderId, now);
            }
            default -> log.info("Mock OMS: 처리 규칙 없는 이벤트 {}", type);
        }
    }

    /** OMS 자체 규칙: 모든 배송이 종료되어야 주문 상태를 바꾼다. 일부 완료는 PARTIALLY_DELIVERED. */
    private void recomputeOrder(String orderId, Instant now) {
        List<String> st = jdbc.queryForList("select status from mock_oms_order_delivery where order_id=?", String.class, orderId);
        long delivered = st.stream().filter("DELIVERED"::equals).count();
        long cancelled = st.stream().filter("CANCELLED"::equals).count();
        String status;
        if (delivered == st.size()) status = "COMPLETED";
        else if (cancelled == st.size()) status = "CANCELLED";
        else if (delivered + cancelled == st.size()) status = "COMPLETED_WITH_CANCELLATION";
        else if (delivered > 0) status = "PARTIALLY_DELIVERED";
        else status = "CONFIRMED";
        boolean done = status.startsWith("COMPLETED");
        jdbc.update("update mock_oms_order set status=?, completed_at=? where order_id=?", status, done ? Timestamp.from(now) : null, orderId);
    }

    public OrderView order(String orderId) {
        var rows = jdbc.queryForList("select * from mock_oms_order where order_id=?", orderId);
        if (rows.isEmpty()) throw ApiException.notFound("ORDER_NOT_FOUND", "주문 없음");
        return view(rows.get(0));
    }

    public List<OrderView> orders(String customerIdOrNull) {
        var rows = customerIdOrNull == null
                ? jdbc.queryForList("select * from mock_oms_order order by created_at desc limit 100")
                : jdbc.queryForList("select * from mock_oms_order where customer_id=? order by created_at desc limit 50", customerIdOrNull);
        return rows.stream().map(this::view).toList();
    }

    public List<Map<String, Object>> inbox() {
        return jdbc.queryForList("select source_system, event_id, event_type, applied_at from mock_oms_inbox order by applied_at desc limit 100");
    }

    private OrderView view(Map<String, Object> r) {
        String orderId = (String) r.get("order_id");
        var ds = jdbc.query("select * from mock_oms_order_delivery where order_id=? order by request_id", (rs, i) -> {
            Timestamp c = rs.getTimestamp("completed_at");
            return new OrderDeliveryView(rs.getString("delivery_id"), rs.getString("request_id"), rs.getInt("package_count"), rs.getInt("weight_grams"),
                    rs.getString("status"), c == null ? null : c.toInstant());
        }, orderId);
        return new OrderView(orderId, (String) r.get("customer_id"), (String) r.get("mfc_id"), (String) r.get("status"),
                ((Number) r.get("total_weight_grams")).intValue(), (String) r.get("address"), (String) r.get("address_detail"),
                ((Number) r.get("lat")).doubleValue(), ((Number) r.get("lng")).doubleValue(), Boolean.TRUE.equals(r.get("split")),
                toInstant(r.get("created_at")), toInstant(r.get("completed_at")), ds);
    }

    private static Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof Instant i) return i;
        return null;
    }
}
