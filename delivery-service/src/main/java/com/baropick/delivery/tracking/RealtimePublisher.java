package com.baropick.delivery.tracking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DispatchOfferRepository;
import com.baropick.delivery.eta.EtaService;

/**
 * 커밋된 상태만 실시간 전파한다 (롤백된 변경은 전송되지 않음).
 * 토픽: /topic/orders/{orderId} (고객, 소유권 검사), /topic/riders/{riderId} (라이더 본인), /topic/admin/deliveries (관리자)
 *
 * 트랜잭션 안에서 발생한 변경은 <b>커밋 직전에 같은 트랜잭션·연결로 메시지를 만들고, 커밋된 뒤에 전송</b>한다.
 * 이전 구현은 커밋 후 리스너에서 새 트랜잭션(REQUIRES_NEW)으로 조회했는데, 그 시점에는 원 트랜잭션의 DB 연결이 아직
 * 반납되지 않아 요청마다 연결 2개가 필요했다. 동시 변경 수가 연결 풀 크기(기본 10)에 이르면 모든 스레드가 두 번째 연결을
 * 기다리며 교착되어 서버가 시간 초과까지 멈췄다 (2026-11-02 부하 테스트에서 발견).
 * 트랜잭션 밖에서 발생한 변경은 짧은 읽기 전용 트랜잭션으로 만들어 바로 보낸다.
 */
@Component
public class RealtimePublisher {
    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private record Out(String destination, Object payload) {}

    private record Batch(List<Out> messages, Runnable afterSend) {
        static final Batch EMPTY = new Batch(List.of(), null);
    }

    private final SimpMessagingTemplate broker;
    private final DeliveryRepository deliveries;
    private final DispatchOfferRepository offers;
    private final TrackingQueryService query;
    private final EtaService eta;
    private final TransactionTemplate readOnlyTx;

    public RealtimePublisher(SimpMessagingTemplate broker, DeliveryRepository deliveries, DispatchOfferRepository offers,
                             TrackingQueryService query, EtaService eta, TransactionTemplate tx) {
        this.broker = broker;
        this.deliveries = deliveries;
        this.offers = offers;
        this.query = query;
        this.eta = eta;
        this.readOnlyTx = new TransactionTemplate(tx.getTransactionManager());
        this.readOnlyTx.setReadOnly(true);
    }

    @EventListener
    public void onDeliveryChanged(DeliveryChanged ev) {
        publishAfterCommit(() -> deliveryBatch(ev));
    }

    @EventListener
    public void onOfferChanged(OfferChanged ev) {
        publishAfterCommit(() -> {
            var o = offers.findById(ev.offerId()).orElse(null);
            if (o == null) return Batch.EMPTY;
            return new Batch(List.of(new Out("/topic/riders/" + ev.riderId(), msg("OFFER_" + ev.status(), ev.status(), query.offerView(o)))), null);
        });
    }

    private Batch deliveryBatch(DeliveryChanged ev) {
        var d = deliveries.findById(ev.deliveryId()).orElse(null);
        if (d == null) return Batch.EMPTY;
        List<Out> out = new ArrayList<>();
        out.add(new Out("/topic/orders/" + d.getOrderId(), msg("DELIVERY_UPDATED", ev.kind(), query.customerView(d))));
        if (d.getRiderId() != null) {
            out.add(new Out("/topic/riders/" + d.getRiderId(), msg("DELIVERY_UPDATED", ev.kind(), query.riderView(d))));
        }
        if (ev.previousRiderId() != null) {
            out.add(new Out("/topic/riders/" + ev.previousRiderId(),
                    msg("ASSIGNMENT_REVOKED", ev.kind(), Map.of("deliveryId", d.getId(), "message", "배정이 해제되어 이 배송의 권한이 회수되었습니다"))));
        }
        out.add(new Out("/topic/admin/deliveries", msg("DELIVERY_UPDATED", ev.kind(), Map.of("deliveryId", d.getId(), "status", d.getStatus().name()))));
        String id = d.getId();
        return new Batch(out, "LOCATION".equals(ev.kind()) ? () -> eta.maybeRecompute(id) : null);
    }

    private void publishAfterCommit(Supplier<Batch> build) {
        if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                private Batch batch = Batch.EMPTY;

                @Override
                public void beforeCommit(boolean readOnly) {
                    // 같은 트랜잭션·연결로 조회 (추가 연결 불필요). 실시간 전송 실패가 업무 트랜잭션을 롤백시키지 않게 한다
                    try {
                        batch = build.get();
                    } catch (RuntimeException e) {
                        log.warn("실시간 메시지 생성 실패(업무 처리는 계속): {}", e.toString());
                    }
                }

                @Override
                public void afterCommit() {
                    send(batch);
                }
            });
            return;
        }
        Batch b;
        try {
            b = readOnlyTx.execute(s -> build.get());
        } catch (RuntimeException e) {
            log.warn("실시간 메시지 생성 실패: {}", e.toString());
            return;
        }
        send(b);
    }

    private void send(Batch b) {
        if (b == null) return;
        for (Out o : b.messages()) {
            try {
                broker.convertAndSend(o.destination(), o.payload());
            } catch (RuntimeException e) {
                log.warn("실시간 전송 실패 {}: {}", o.destination(), e.toString());
            }
        }
        if (b.afterSend() != null) b.afterSend().run();
    }

    private static Object msg(String type, String kind, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("kind", kind);
        m.put("data", data);
        return m;
    }
}
