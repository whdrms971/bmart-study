package com.baropick.delivery.testsupport.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.dispatch.DispatchService;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DispatchAttemptRepository;
import com.baropick.delivery.domain.DispatchOfferRepository;
import com.baropick.delivery.domain.LocationVerdict;
import com.baropick.delivery.domain.OutboxEventRepository;
import com.baropick.delivery.domain.RiderLocationRepository;
import com.baropick.delivery.domain.RiderRepository;
import com.baropick.delivery.domain.StatusHistoryRepository;
import com.baropick.delivery.eta.EtaService;
import com.baropick.delivery.execution.ExecutionService;
import com.baropick.delivery.kakao.KakaoClient;
import com.baropick.delivery.mfc.MfcRegistry;
import com.baropick.delivery.tracking.TrackingQueryService;

/** 테스트 관리자 조회·조작 (local-test 전용). 운영 관제 화면이 아니다. */
@RestController
@Profile("local-test")
public class AdminController {
    private final DeliveryRepository deliveries;
    private final StatusHistoryRepository history;
    private final DispatchOfferRepository offers;
    private final DispatchAttemptRepository attempts;
    private final RiderLocationRepository locations;
    private final RiderRepository riders;
    private final OutboxEventRepository outbox;
    private final TrackingQueryService query;
    private final ExecutionService execution;
    private final DispatchService dispatch;
    private final EtaService eta;
    private final KakaoClient kakao;
    private final MfcRegistry mfcs;

    public AdminController(DeliveryRepository deliveries, StatusHistoryRepository history, DispatchOfferRepository offers,
                           DispatchAttemptRepository attempts, RiderLocationRepository locations, RiderRepository riders,
                           OutboxEventRepository outbox, TrackingQueryService query, ExecutionService execution, DispatchService dispatch,
                           EtaService eta, KakaoClient kakao, MfcRegistry mfcs) {
        this.deliveries = deliveries;
        this.history = history;
        this.offers = offers;
        this.attempts = attempts;
        this.locations = locations;
        this.riders = riders;
        this.outbox = outbox;
        this.query = query;
        this.execution = execution;
        this.dispatch = dispatch;
        this.eta = eta;
        this.kakao = kakao;
        this.mfcs = mfcs;
    }

    public record DeliveryRow(String deliveryId, String orderId, String customerId, String mfcId, String status, String locationMode,
                              String riderId, int assignmentVersion, boolean packagesReady, String dispatchNote, boolean dispatchStalled,
                              Instant createdAt, Instant updatedAt) {}

    private static DeliveryRow row(Delivery d) {
        return new DeliveryRow(d.getId(), d.getOrderId(), d.getCustomerId(), d.getMfcId(), d.getStatus().name(), d.getLocationMode().name(),
                d.getRiderId(), d.getAssignmentVersion(), d.isPackagesReady(), d.getDispatchNote(), d.isDispatchStalled(), d.getCreatedAt(),
                d.getUpdatedAt());
    }

    @GetMapping("/test-api/admin/deliveries")
    @Transactional(readOnly = true)
    public List<DeliveryRow> deliveries(Authentication auth) {
        AdminGuard.require(auth);
        return deliveries.findTop200ByOrderByCreatedAtDesc().stream().map(AdminController::row).toList();
    }

    @GetMapping("/test-api/admin/deliveries/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> delivery(Authentication auth, @PathVariable String id) {
        AdminGuard.require(auth);
        Delivery d = deliveries.findById(id).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", id));
        var locs = locations.findTop100ByDeliveryIdOrderByIdDesc(id).stream().map(l -> Map.of(
                "seq", l.getSeq(), "source", l.getSource().name(), "scenarioId", String.valueOf(l.getScenarioId()),
                "lat", String.valueOf(l.getLat()), "lng", String.valueOf(l.getLng()), "measuredAt", String.valueOf(l.getMeasuredAt()),
                "receivedAt", l.getReceivedAt().toString(), "verdict", l.getVerdict().name(), "reason", String.valueOf(l.getReason()))).toList();
        Map<String, Long> verdicts = new java.util.LinkedHashMap<>();
        for (LocationVerdict v : LocationVerdict.values()) verdicts.put(v.name(), locations.countByDeliveryIdAndVerdict(id, v));
        return Map.of(
                "delivery", row(d),
                "rider", query.riderView(d),
                "customer", query.customerView(d),
                "history", history.findByDeliveryIdOrderById(id),
                "attempts", attempts.findByDeliveryIdOrderByAttemptNo(id),
                "offers", offers.findByDeliveryIdOrderByOfferedAt(id),
                "locationVerdicts", verdicts,
                "recentLocations", locs,
                "outbox", outbox.findByAggregateIdOrderByCreatedAt(id));
    }

    public record ReasonRequest(String reason) {}

    @PostMapping("/test-api/admin/deliveries/{id}/reassign")
    public DeliveryRow reassign(Authentication auth, @PathVariable String id, @RequestBody(required = false) ReasonRequest req) {
        var a = AdminGuard.require(auth);
        return row(execution.reassignByAdmin(id, a.userId(), req == null ? null : req.reason()));
    }

    @PostMapping("/test-api/admin/deliveries/{id}/resume-dispatch")
    public Map<String, String> resumeDispatch(Authentication auth, @PathVariable String id) {
        var a = AdminGuard.require(auth);
        dispatch.resumeDispatch(id, "ADMIN:" + a.userId());
        return Map.of("result", "OK");
    }

    @PostMapping("/test-api/admin/deliveries/{id}/eta/recompute")
    public Object recomputeEta(Authentication auth, @PathVariable String id) {
        AdminGuard.require(auth);
        eta.recomputeNow(id);
        return query.customerView(deliveries.findById(id).orElseThrow()).eta();
    }

    @GetMapping("/test-api/admin/riders")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> riders(Authentication auth) {
        AdminGuard.require(auth);
        return riders.findAll().stream().map(r -> Map.<String, Object>of("riderId", r.getId(), "dutyStatus", r.getDutyStatus().name(),
                "lastLat", String.valueOf(r.getLastLat()), "lastLng", String.valueOf(r.getLastLng()),
                "lastLocationAt", String.valueOf(r.getLastLocationAt()))).toList();
    }

    @GetMapping("/test-api/admin/outbox")
    @Transactional(readOnly = true)
    public Object outbox(Authentication auth) {
        AdminGuard.require(auth);
        return outbox.findTop100ByOrderByCreatedAtDesc();
    }

    @GetMapping("/test-api/admin/kakao-usage")
    public Object kakaoUsage(Authentication auth) {
        AdminGuard.require(auth);
        return Map.of("configured", kakao.configured(), "usage", kakao.usage());
    }

    @GetMapping("/test-api/mfcs")
    public Object mfcs() { return mfcs.all(); }

    /** 고객 앱 테스트 주문용: FIXTURE 경로가 준비된 가상 테스트 배송지 목록 (모든 테스트 계정 조회 가능). */
    @GetMapping("/test-api/test-destinations")
    public Object testDestinations() {
        return routes == null ? List.of() : routes.fixtures().stream().map(f -> Map.of(
                "fixtureId", f.id(), "mfcId", f.mfcId(), "label", f.destinationLabel(), "address", f.destinationAddress(),
                "latitude", f.destLat(), "longitude", f.destLng())).toList();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.baropick.delivery.testsupport.simulation.RouteProviders routes;

    public record Relocate(Double latitude, Double longitude) {}

    @PutMapping("/test-api/admin/mfcs/{id}")
    public Object relocate(Authentication auth, @PathVariable String id, @RequestBody Relocate req) {
        AdminGuard.require(auth);
        if (req.latitude() == null || req.longitude() == null) throw ApiException.badRequest("INVALID_COORDINATE", "좌표 필요");
        return mfcs.relocate(id, req.latitude(), req.longitude());
    }
}
