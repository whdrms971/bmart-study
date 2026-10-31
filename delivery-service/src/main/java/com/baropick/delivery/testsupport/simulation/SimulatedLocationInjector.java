package com.baropick.delivery.testsupport.simulation;

import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.baropick.delivery.domain.LocationMode;
import com.baropick.delivery.tracking.LocationIngestService;
import com.baropick.delivery.tracking.LocationIngestService.IngestContext;
import com.baropick.delivery.tracking.LocationIngestService.IngestResult;
import com.baropick.delivery.tracking.LocationIngestService.LocationPoint;

/**
 * 서버 내부 시뮬레이션 주입 어댑터. 외부 HTTP 경로가 없으며 SimulationService 만 사용한다.
 * 실 GPS 와 동일한 LocationIngestService(검증·저장·권한·실시간 전파)를 통과한다.
 */
@Component
@Profile("local-test")
class SimulatedLocationInjector {
    private final LocationIngestService ingest;

    SimulatedLocationInjector(LocationIngestService ingest) { this.ingest = ingest; }

    IngestResult inject(String scenarioId, String deliveryId, String riderId, int assignmentVersion, Instant virtualNow,
                        List<LocationPoint> points) {
        return ingest.ingest(new IngestContext(deliveryId, riderId, LocationMode.SIMULATED_ROUTE, scenarioId, virtualNow, assignmentVersion), points);
    }
}
