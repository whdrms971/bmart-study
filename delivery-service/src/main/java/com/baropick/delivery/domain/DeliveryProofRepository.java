package com.baropick.delivery.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryProofRepository extends JpaRepository<DeliveryProof, String> {
    Optional<DeliveryProof> findByDeliveryId(String deliveryId);
}
