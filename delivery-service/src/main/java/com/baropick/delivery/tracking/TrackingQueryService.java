package com.baropick.delivery.tracking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Geo;
import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryEta;
import com.baropick.delivery.domain.DeliveryEtaRepository;
import com.baropick.delivery.domain.DeliveryPackage;
import com.baropick.delivery.domain.DeliveryPackageRepository;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.DispatchOffer;
import com.baropick.delivery.domain.EtaStatus;
import com.baropick.delivery.domain.LatestLocation;
import com.baropick.delivery.domain.LatestLocationRepository;
import com.baropick.delivery.domain.LocationMode;
import com.baropick.delivery.domain.PackageStatus;
import com.baropick.delivery.mfc.MfcRegistry;
import com.baropick.delivery.tracking.TrackingViews.CustomerDelivery;
import com.baropick.delivery.tracking.TrackingViews.Destination;
import com.baropick.delivery.tracking.TrackingViews.Eta;
import com.baropick.delivery.tracking.TrackingViews.Mfc;
import com.baropick.delivery.tracking.TrackingViews.OrderTracking;
import com.baropick.delivery.tracking.TrackingViews.PackageSummary;
import com.baropick.delivery.tracking.TrackingViews.PackageView;
import com.baropick.delivery.tracking.TrackingViews.RiderDelivery;
import com.baropick.delivery.tracking.TrackingViews.RiderPosition;

@Service
@Transactional(readOnly = true)
public class TrackingQueryService {

    private final DeliveryRepository deliveries;
    private final DeliveryPackageRepository packages;
    private final LatestLocationRepository latest;
    private final DeliveryEtaRepository etas;
    private final MfcRegistry mfcs;
    private final DeliveryProperties.Policy policy;
    private final Clock clock;

    private final com.baropick.delivery.execution.ArrivalPolicy arrivalPolicy;
    private final com.baropick.delivery.domain.ArrivalExceptionRepository arrivalExceptions;

    public TrackingQueryService(DeliveryRepository deliveries, DeliveryPackageRepository packages, LatestLocationRepository latest,
                                DeliveryEtaRepository etas, MfcRegistry mfcs, DeliveryProperties props, Clock clock,
                                com.baropick.delivery.execution.ArrivalPolicy arrivalPolicy,
                                com.baropick.delivery.domain.ArrivalExceptionRepository arrivalExceptions) {
        this.arrivalPolicy = arrivalPolicy;
        this.arrivalExceptions = arrivalExceptions;
        this.deliveries = deliveries;
        this.packages = packages;
        this.latest = latest;
        this.etas = etas;
        this.mfcs = mfcs;
        this.policy = props.policy();
        this.clock = clock;
    }

    /** 고객 소유 주문만 조회. 소유하지 않은 주문은 존재 여부도 노출하지 않는다(404). */
    public OrderTracking orderTrackingForCustomer(String orderId, String customerId) {
        List<Delivery> ds = deliveries.findByOrderIdOrderByCreatedAt(orderId).stream()
                .filter(d -> d.getCustomerId().equals(customerId)).toList();
        if (ds.isEmpty()) throw ApiException.notFound("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다");
        return new OrderTracking(orderId, ds.stream().map(this::customerView).toList(), clock.instant());
    }

    public boolean customerOwnsOrder(String orderId, String customerId) {
        return deliveries.findByOrderIdOrderByCreatedAt(orderId).stream().anyMatch(d -> d.getCustomerId().equals(customerId));
    }

    public CustomerDelivery customerView(Delivery d) {
        boolean visible = locationVisibleToCustomer(d.getStatus());
        RiderPosition rider = visible ? latest.findById(d.getId()).map(this::position).orElse(null) : null;
        return new CustomerDelivery(d.getId(), d.getOrderId(), d.getStatus(), TrackingViews.label(d.getStatus()), d.getLocationMode().name(),
                d.getLocationMode() == LocationMode.SIMULATED_ROUTE, destination(d), visible, rider, eta(d), packageSummary(d),
                d.getUpdatedAt(), d.getPickedUpAt(), d.getDeliveredAt(), d.getCancelledAt(), clock.instant());
    }

    /** 공개 시점 정책(P-05). 정책 미설정이면 공개하지 않는다. 완료·취소 후 공개 종료(D-16). */
    public boolean locationVisibleToCustomer(DeliveryStatus s) {
        DeliveryStatus from = policy.exposeRiderLocationFrom();
        if (from == null || s.isTerminal()) return false;
        return s.atOrAfter(from);
    }

    public RiderDelivery riderView(Delivery d) {
        var site = mfcs.find(d.getMfcId()).orElse(null);
        Mfc mfc = new Mfc(d.getMfcId(), site == null ? d.getMfcId() : site.name(), site != null && site.virtual(), d.getOriginLat(), d.getOriginLng());
        List<PackageView> pv = packages.findByDeliveryIdOrderByPackageId(d.getId()).stream()
                .map(p -> new PackageView(p.getPackageId(), p.getStatus().name(), p.getHandedOverAt(), p.getLastError())).toList();
        RiderPosition pos = latest.findById(d.getId()).map(this::position).orElse(null);
        return new RiderDelivery(d.getId(), d.getOrderId(), d.getStatus(), TrackingViews.label(d.getStatus()), d.getLocationMode().name(),
                d.getLocationMode() == LocationMode.SIMULATED_ROUTE, mfc, destination(d), d.getCustomerNote(), d.getExpectedPackageCount(),
                d.isPackagesReady(), pv, pos, eta(d), d.getAssignmentVersion(), d.getUpdatedAt(), arrivalView(d));
    }

    /** 배송 중·배송지 도착 상태에서만 도착 판정 정보를 준다. 정책 미설정이면 null(앱은 서버 판정 결과만 따른다). */
    private TrackingViews.ArrivalView arrivalView(Delivery d) {
        if (d.getStatus() != DeliveryStatus.IN_TRANSIT && d.getStatus() != DeliveryStatus.ARRIVED_AT_DESTINATION) return null;
        var c = arrivalPolicy.evaluate(d);
        if (c.maxDistanceMeters() == null || c.maxLocationAgeSeconds() == null || c.maxAccuracyMeters() == null) return null;
        var check = d.getStatus() == DeliveryStatus.IN_TRANSIT
                ? new TrackingViews.ArrivalCheckView(c.allowed(), c.code().name(), c.message(), c.distanceMeters(), c.accuracyMeters(),
                        c.locationAgeSeconds(), c.locationSource())
                : null;
        var ex = arrivalExceptions.findFirstByDeliveryIdOrderByRequestedAtDesc(d.getId())
                .map(e -> new TrackingViews.ArrivalExceptionView(e.getId(), e.getReasonCode().name(), e.getStatus().name(), e.getRequestedAt(),
                        e.getDecisionNote(), e.getDecidedAt()))
                .orElse(null);
        return new TrackingViews.ArrivalView(c.maxDistanceMeters(), c.maxLocationAgeSeconds(), c.maxAccuracyMeters(), check, ex);
    }

    public TrackingViews.Offer offerView(DispatchOffer o) {
        Delivery d = deliveries.findById(o.getDeliveryId()).orElseThrow();
        var site = mfcs.find(d.getMfcId()).orElse(null);
        Mfc mfc = new Mfc(d.getMfcId(), site == null ? d.getMfcId() : site.name(), site != null && site.virtual(), d.getOriginLat(), d.getOriginLng());
        long remaining = Math.max(0, Duration.between(clock.instant(), o.getExpiresAt()).getSeconds());
        double dist = Geo.distanceMeters(d.getOriginLat(), d.getOriginLng(), d.getDestLat(), d.getDestLng());
        return new TrackingViews.Offer(o.getId(), d.getId(), d.getOrderId(), o.getStatus().name(), o.getOfferedAt(), o.getExpiresAt(), remaining,
                mfc, d.getDestAddress(), Math.round(dist), d.getExpectedPackageCount(), d.getTotalWeightGrams(), d.getLocationMode().name());
    }

    private Destination destination(Delivery d) {
        return new Destination(d.getDestLat(), d.getDestLng(), d.getDestAddress(), d.getDestAddressDetail());
    }

    private RiderPosition position(LatestLocation l) {
        long age = Math.max(0, Duration.between(l.getReceivedAt(), clock.instant()).getSeconds());
        return new RiderPosition(l.getLat(), l.getLng(), l.getAccuracyM(), l.getMeasuredAt(), l.getReceivedAt(), l.getSource().name(), age);
    }

    private PackageSummary packageSummary(Delivery d) {
        List<DeliveryPackage> ps = packages.findByDeliveryIdOrderByPackageId(d.getId());
        int total = d.isPackagesReady() ? ps.size() : d.getExpectedPackageCount();
        return new PackageSummary(total, (int) ps.stream().filter(p -> p.getStatus() == PackageStatus.HANDED_OVER).count());
    }

    private Eta eta(Delivery d) {
        if (d.getStatus().isTerminal()) {
            return new Eta(EtaStatus.NOT_APPLICABLE.name(), null, null, null, null, null, "배송이 종료되었습니다");
        }
        if (!d.getStatus().atOrAfter(DeliveryStatus.IN_TRANSIT)) {
            return new Eta(EtaStatus.NOT_APPLICABLE.name(), null, null, null, null, null, "배송 출발 후 표시됩니다");
        }
        if (policy.etaMinIntervalSeconds() == null) {
            return new Eta(EtaStatus.UNAVAILABLE.name(), null, null, null, null, null, "예상시간 확인 불가 (ETA 정책 미설정)");
        }
        DeliveryEta e = etas.findById(d.getId()).orElse(null);
        if (e == null) return new Eta(EtaStatus.UNAVAILABLE.name(), null, null, null, null, null, "도착예상시간 확인 중");
        if (e.getStatus() == EtaStatus.OK && "NEAR_DESTINATION".equals(e.getProvider())) {
            return new Eta("OK", 0, e.getDistanceMeters(), e.getComputedAt(), e.getComputedAt(), e.getProvider(),
                    "배송지 근처 (직선 " + e.getDistanceMeters() + "m) — 라이더의 도착·전달 처리를 기다리는 중");
        }
        if (e.getStatus() == EtaStatus.OK) {
            Instant arrival = e.getComputedAt().plusSeconds(e.getEtaSeconds());
            return new Eta("OK", e.getEtaSeconds(), e.getDistanceMeters(), e.getComputedAt(), arrival, e.getProvider(),
                    "카카오모빌리티 자동차 경로 기준 (실시각 기준, 시뮬레이션 배속 미반영)");
        }
        String last = e.getComputedAt() == null ? "" : " (마지막 정상 계산 " + e.getComputedAt() + ")";
        return new Eta(EtaStatus.UNAVAILABLE.name(), null, null, e.getComputedAt(), null, e.getProvider(),
                "예상시간 확인 불가: " + (e.getLastError() == null ? "" : e.getLastError()) + last);
    }
}
