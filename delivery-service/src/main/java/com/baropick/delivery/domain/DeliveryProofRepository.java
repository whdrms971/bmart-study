package com.baropick.delivery.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryProofRepository extends JpaRepository<DeliveryProof, String> {
    Optional<DeliveryProof> findByDeliveryId(String deliveryId);

    /** P-09: 보관 기간이 지났고 아직 사진을 지우지 않은 증빙 */
    java.util.List<DeliveryProof> findTop500ByCreatedAtBeforeAndPhotoPurgedAtIsNullOrderByCreatedAt(java.time.Instant cutoff);
}
