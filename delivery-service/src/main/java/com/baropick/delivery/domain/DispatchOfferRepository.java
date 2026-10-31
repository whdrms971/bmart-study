package com.baropick.delivery.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchOfferRepository extends JpaRepository<DispatchOffer, String> {
    List<DispatchOffer> findByRiderIdAndStatusOrderByOfferedAtDesc(String riderId, OfferStatus status);
    List<DispatchOffer> findByAttemptId(String attemptId);
    List<DispatchOffer> findByDeliveryIdOrderByOfferedAt(String deliveryId);
    List<DispatchOffer> findByStatusAndExpiresAtBefore(OfferStatus status, Instant at);
    boolean existsByDeliveryIdAndRiderIdAndStatus(String deliveryId, String riderId, OfferStatus status);
}
