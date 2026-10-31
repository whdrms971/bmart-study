package com.baropick.delivery.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface DeliveryRepository extends JpaRepository<Delivery, String> {
    /** 상태 전이 직렬화용 행 잠금 (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> findForUpdate(@Param("id") String id);

    Optional<Delivery> findBySourceSystemAndRequestId(String sourceSystem, String requestId);

    List<Delivery> findByOrderIdOrderByCreatedAt(String orderId);

    List<Delivery> findByStatusAndDispatchStalledFalse(DeliveryStatus status);

    List<Delivery> findByStatus(DeliveryStatus status);

    List<Delivery> findByRiderIdAndStatusIn(String riderId, Collection<DeliveryStatus> statuses);

    long countByRiderIdAndStatusIn(String riderId, Collection<DeliveryStatus> statuses);

    List<Delivery> findTop200ByOrderByCreatedAtDesc();
}
