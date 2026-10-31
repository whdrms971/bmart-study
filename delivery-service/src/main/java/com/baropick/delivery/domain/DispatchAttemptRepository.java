package com.baropick.delivery.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchAttemptRepository extends JpaRepository<DispatchAttempt, String> {
    Optional<DispatchAttempt> findFirstByDeliveryIdOrderByAttemptNoDesc(String deliveryId);
    List<DispatchAttempt> findByDeliveryIdOrderByAttemptNo(String deliveryId);
}
