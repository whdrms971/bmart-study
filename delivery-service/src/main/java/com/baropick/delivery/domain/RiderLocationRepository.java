package com.baropick.delivery.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderLocationRepository extends JpaRepository<RiderLocation, Long> {
    boolean existsByDeliveryIdAndSourceAndRiderIdAndSeq(String deliveryId, LocationMode source, String riderId, long seq);
    List<RiderLocation> findTop100ByDeliveryIdOrderByIdDesc(String deliveryId);
    List<RiderLocation> findByDeliveryIdOrderById(String deliveryId);
    long countByDeliveryIdAndVerdict(String deliveryId, LocationVerdict verdict);

    /** P-09: 수신 시각이 cutoff 이전인 위치 이력 삭제 (최신 위치 테이블은 별도, 삭제하지 않음) */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from RiderLocation l where l.receivedAt < :cutoff")
    int deleteReceivedBefore(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);
}
