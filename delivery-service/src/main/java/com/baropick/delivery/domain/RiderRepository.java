package com.baropick.delivery.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderRepository extends JpaRepository<Rider, String> {
    List<Rider> findByDutyStatus(DutyStatus status);
}
