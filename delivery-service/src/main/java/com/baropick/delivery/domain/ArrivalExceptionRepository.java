package com.baropick.delivery.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ArrivalExceptionRepository extends JpaRepository<ArrivalException, String> {
    Optional<ArrivalException> findFirstByDeliveryIdAndStatus(String deliveryId, ArrivalException.Status status);

    Optional<ArrivalException> findFirstByDeliveryIdOrderByRequestedAtDesc(String deliveryId);

    List<ArrivalException> findByStatusOrderByRequestedAtAsc(ArrivalException.Status status);

    List<ArrivalException> findTop100ByOrderByRequestedAtDesc();
}
