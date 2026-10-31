package com.baropick.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.baropick.delivery.support.IntegrationTestBase;

/** D-06 WebSocket: 본인 주문 토픽만 구독 가능, 위치 갱신이 실제 서버 경로로 푸시된다. */
class WebSocketAuthIT extends IntegrationTestBase {

    private StompSession connect(String userId, BlockingQueue<String> errors) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter() {
            @Override protected boolean supportsMimeType(org.springframework.messaging.MessageHeaders headers) { return true; }
        });
        StompHeaders h = new StompHeaders();
        if (userId != null) h.add("Authorization", "Bearer " + token(userId));
        return client.connectAsync("ws://127.0.0.1:" + port + "/ws", new WebSocketHttpHeaders(), h, new StompSessionHandlerAdapter() {
            @Override public void handleTransportError(StompSession s, Throwable ex) { errors.add("transport:" + ex.getMessage()); }
            @Override public void handleException(StompSession s, org.springframework.messaging.simp.stomp.StompCommand c, StompHeaders hd, byte[] p, Throwable ex) {
                errors.add("ex:" + ex.getMessage());
            }
            @Override public void handleFrame(StompHeaders headers, Object payload) { errors.add("error-frame:" + headers.getFirst("message")); }
        }).get(5, TimeUnit.SECONDS);
    }

    private StompFrameHandler into(BlockingQueue<String> q) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { q.add((String) payload); }
        };
    }

    @Test
    @DisplayName("D-06 타인 주문 토픽 구독 거부 + 본인 구독은 시뮬레이션 위치 푸시 수신(SIMULATED_ROUTE)")
    void subscriptionAuthorization() throws Exception {
        String d = remoteDelivery("C-TEST-01", 1);
        String orderId = get("ADMIN-TEST", "/test-api/admin/deliveries/" + d).text("/delivery/orderId");

        BlockingQueue<String> errs = new LinkedBlockingQueue<>();
        StompSession intruder = connect("C-TEST-02", errs);
        BlockingQueue<String> leaked = new LinkedBlockingQueue<>();
        intruder.subscribe("/topic/orders/" + orderId, into(leaked));
        String err = errs.poll(5, TimeUnit.SECONDS);
        assertThat(err).as("타인 구독은 오류 프레임/연결 종료").isNotNull();

        BlockingQueue<String> errs2 = new LinkedBlockingQueue<>();
        StompSession owner = connect("C-TEST-01", errs2);
        BlockingQueue<String> msgs = new LinkedBlockingQueue<>();
        owner.subscribe("/topic/orders/" + orderId, into(msgs));
        Thread.sleep(300);

        pickUp(d, "R-TEST-01");
        post("R-TEST-01", "/api/rider/deliveries/" + d + "/start", null);
        String sid = post("ADMIN-TEST", "/test-api/simulations", Map.of("deliveryId", d, "riderId", "R-TEST-01", "routeSource", "FIXTURE",
                "fixtureId", FIXTURE, "rate", 10)).text("/scenarioId");
        post("ADMIN-TEST", "/test-api/simulations/" + sid + "/prepare", null);
        post("ADMIN-TEST", "/test-api/simulations/" + sid + "/start", null);
        msgs.clear();
        sim().tick(sid);
        String got = null;
        for (int i = 0; i < 10 && got == null; i++) {
            String m = msgs.poll(2, TimeUnit.SECONDS);
            if (m != null && m.contains("\"kind\":\"LOCATION\"")) got = m;
        }
        assertThat(got).as("위치 푸시 수신").isNotNull();
        assertThat(got).contains("SIMULATED_ROUTE");
        assertThat(leaked).isEmpty();
        assertThat(errs2).isEmpty();

        // 인증 없이 CONNECT
        BlockingQueue<String> e3 = new LinkedBlockingQueue<>();
        boolean failed;
        try {
            StompSession anon = connect(null, e3);
            failed = e3.poll(3, TimeUnit.SECONDS) != null || !anon.isConnected();
        } catch (Exception ex) {
            failed = true;
        }
        assertThat(failed).isTrue();
    }

    @org.springframework.beans.factory.annotation.Autowired com.baropick.delivery.testsupport.simulation.SimulationService simulation;
    private com.baropick.delivery.testsupport.simulation.SimulationService sim() { return simulation; }

    @SuppressWarnings("unused")
    private static final List<String> NOTE = List.of();
}
