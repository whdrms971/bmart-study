package com.baropick.delivery.tracking;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DispatchOfferRepository;
import com.baropick.delivery.eta.EtaService;

/**
 * 커밋된 상태만 실시간 전파한다 (롤백된 변경은 전송되지 않음).
 * 토픽: /topic/orders/{orderId} (고객, 소유권 검사), /topic/riders/{riderId} (라이더 본인), /topic/admin/deliveries (관리자)
 */
@Component
public class RealtimePublisher {

    private final SimpMessagingTemplate broker;
    private final DeliveryRepository deliveries;
    private final DispatchOfferRepository offers;
    private final TrackingQueryService query;
    private final EtaService eta;

    public RealtimePublisher(SimpMessagingTemplate broker, DeliveryRepository deliveries, DispatchOfferRepository offers,
                             TrackingQueryService query, EtaService eta) {
        this.broker = broker;
        this.deliveries = deliveries;
        this.offers = offers;
        this.query = query;
        this.eta = eta;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onDeliveryChanged(DeliveryChanged ev) {
        var d = deliveries.findById(ev.deliveryId()).orElse(null);
        if (d == null) return;
        send("/topic/orders/" + d.getOrderId(), msg("DELIVERY_UPDATED", ev.kind(), query.customerView(d)));
        if (d.getRiderId() != null) {
            send("/topic/riders/" + d.getRiderId(), msg("DELIVERY_UPDATED", ev.kind(), query.riderView(d)));
        }
        if (ev.previousRiderId() != null) {
            send("/topic/riders/" + ev.previousRiderId(),
                    msg("ASSIGNMENT_REVOKED", ev.kind(), Map.of("deliveryId", d.getId(), "message", "배정이 해제되어 이 배송의 권한이 회수되었습니다")));
        }
        send("/topic/admin/deliveries", msg("DELIVERY_UPDATED", ev.kind(), Map.of("deliveryId", d.getId(),
                "status", d.getStatus().name())));
        if ("LOCATION".equals(ev.kind())) eta.maybeRecompute(d.getId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onOfferChanged(OfferChanged ev) {
        var o = offers.findById(ev.offerId()).orElse(null);
        if (o == null) return;
        send("/topic/riders/" + ev.riderId(), msg("OFFER_" + ev.status(), ev.status(), query.offerView(o)));
    }

    private void send(String destination, Object payload) {
        broker.convertAndSend(destination, payload);
    }

    private static Object msg(String type, String kind, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("kind", kind);
        m.put("data", data);
        return m;
    }
}
