package com.baropick.delivery.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryPackageRepository extends JpaRepository<DeliveryPackage, String> {
    List<DeliveryPackage> findByDeliveryIdOrderByPackageId(String deliveryId);
}
