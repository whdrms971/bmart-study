package com.baropick.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LatestLocationRepository extends JpaRepository<LatestLocation, String> {
}
