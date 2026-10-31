package com.baropick.delivery.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderLocationRepository extends JpaRepository<RiderLocation, Long> {
    boolean existsByDeliveryIdAndSourceAndRiderIdAndSeq(String deliveryId, LocationMode source, String riderId, long seq);
    List<RiderLocation> findTop100ByDeliveryIdOrderByIdDesc(String deliveryId);
    List<RiderLocation> findByDeliveryIdOrderById(String deliveryId);
    long countByDeliveryIdAndVerdict(String deliveryId, LocationVerdict verdict);
}
