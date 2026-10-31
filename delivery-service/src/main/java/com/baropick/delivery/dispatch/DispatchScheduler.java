package com.baropick.delivery.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;

/**
 * 배차 진행 스케줄러. 제안 만료 처리와 REQUESTED 배송의 다음 시도 생성만 한다.
 * (어떤 스케줄러도 배송을 DELIVERED 로 전이하지 않는다 — 자동완료 금지, DECISIONS C-01)
 */
@Component
@ConditionalOnProperty(name = "delivery.dispatch.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class DispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(DispatchScheduler.class);

    private final DispatchService dispatch;
    private final DeliveryRepository deliveries;

    public DispatchScheduler(DispatchService dispatch, DeliveryRepository deliveries) {
        this.dispatch = dispatch;
        this.deliveries = deliveries;
    }

    @Scheduled(fixedDelayString = "${delivery.dispatch.tick-ms:2000}")
    public void tick() {
        try {
            dispatch.expireDueOffers();
        } catch (RuntimeException e) {
            log.warn("제안 만료 처리 실패: {}", e.getMessage());
        }
        for (Delivery d : deliveries.findByStatusAndDispatchStalledFalse(DeliveryStatus.REQUESTED)) {
            try {
                dispatch.tryDispatch(d.getId());
            } catch (ApiException e) {
                log.debug("배차 보류 {}: {} {}", d.getId(), e.code(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("배차 시도 실패 {}: {}", d.getId(), e.getMessage());
            }
        }
    }
}
