package com.baropick.delivery.execution;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.baropick.delivery.auth.Actor;
import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Geo;
import com.baropick.delivery.dispatch.DispatchService;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.DispatchOfferRepository;
import com.baropick.delivery.domain.DutyStatus;
import com.baropick.delivery.domain.LocationMode;
import com.baropick.delivery.domain.OfferStatus;
import com.baropick.delivery.domain.ProofMethod;
import com.baropick.delivery.domain.Rider;
import com.baropick.delivery.domain.RiderRepository;
import com.baropick.delivery.handover.HandoverService;
import com.baropick.delivery.tracking.LocationIngestService;
import com.baropick.delivery.tracking.LocationIngestService.IngestContext;
import com.baropick.delivery.tracking.LocationIngestService.LocationPoint;
import com.baropick.delivery.tracking.TrackingQueryService;
import com.baropick.delivery.tracking.TrackingViews;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 라이더 앱 API. 라이더 ID 는 항상 토큰 subject 에서 얻는다. */
@RestController
public class RiderApiController {

    private final RiderRepository riders;
    private final DeliveryRepository deliveries;
    private final DispatchOfferRepository offers;
    private final DispatchService dispatch;
    private final ExecutionService execution;
    private final HandoverService handover;
    private final LocationIngestService ingest;
    private final TrackingQueryService query;
    private final Clock clock;

    public RiderApiController(RiderRepository riders, DeliveryRepository deliveries, DispatchOfferRepository offers, DispatchService dispatch,
                              ExecutionService execution, HandoverService handover, LocationIngestService ingest, TrackingQueryService query,
                              Clock clock) {
        this.riders = riders;
        this.deliveries = deliveries;
        this.offers = offers;
        this.dispatch = dispatch;
        this.execution = execution;
        this.handover = handover;
        this.ingest = ingest;
        this.query = query;
        this.clock = clock;
    }

    private Rider me(Authentication auth) {
        String id = Actor.from(auth).userId();
        return riders.findById(id).orElseThrow(() -> ApiException.forbidden("RIDER_NOT_REGISTERED", "등록되지 않은 라이더입니다"));
    }

    public record RiderStatus(String riderId, String displayName, DutyStatus dutyStatus, Double lastLatitude, Double lastLongitude,
                              java.time.Instant lastLocationAt, List<TrackingViews.RiderDelivery> activeDeliveries) {}

    @GetMapping("/api/rider/me")
    @Transactional(readOnly = true)
    public RiderStatus status(Authentication auth) {
        Rider r = me(auth);
        var active = deliveries.findByRiderIdAndStatusIn(r.getId(), DeliveryStatus.RIDER_ACTIVE).stream().map(query::riderView).toList();
        return new RiderStatus(r.getId(), r.getDisplayName(), r.getDutyStatus(), r.getLastLat(), r.getLastLng(), r.getLastLocationAt(), active);
    }

    public record DutyRequest(@NotNull DutyStatus dutyStatus) {}

    /** 운행 종료/신규배차 중지는 새 제안만 막는다. 수행 중 배송은 자동 취소하지 않는다. */
    @PostMapping("/api/rider/me/duty")
    @Transactional
    public RiderStatus duty(Authentication auth, @Valid @RequestBody DutyRequest req) {
        Rider r = me(auth);
        r.setDuty(req.dutyStatus(), clock.instant());
        return status(auth);
    }

    public record IdleLocation(@NotNull Double latitude, @NotNull Double longitude, Double accuracyMeters, @NotNull java.time.Instant measuredAt) {}

    /** 배차 반경 판정용 대기 위치(배송 위치 원장과 별개). */
    @PostMapping("/api/rider/me/location")
    @Transactional
    public void idleLocation(Authentication auth, @Valid @RequestBody IdleLocation req) {
        if (!Geo.validLat(req.latitude()) || !Geo.validLng(req.longitude())) throw ApiException.badRequest("INVALID_COORDINATE", "좌표 범위 오류");
        if (req.accuracyMeters() != null && (!Double.isFinite(req.accuracyMeters()) || req.accuracyMeters() < 0)) {
            throw ApiException.badRequest("INVALID_ACCURACY", "정확도 음수/NaN");
        }
        var now = clock.instant();
        if (req.measuredAt().isAfter(now.plusSeconds(30))) throw ApiException.badRequest("FUTURE_TIMESTAMP", "측정시각이 미래입니다");
        me(auth).updateIdleLocation(req.latitude(), req.longitude(), req.measuredAt(), now);
    }

    @GetMapping("/api/rider/offers")
    @Transactional(readOnly = true)
    public List<TrackingViews.Offer> myOffers(Authentication auth) {
        String id = Actor.from(auth).userId();
        var now = clock.instant();
        return offers.findByRiderIdAndStatusOrderByOfferedAtDesc(id, OfferStatus.OFFERED).stream()
                .filter(o -> o.getExpiresAt().isAfter(now)).map(query::offerView).toList();
    }

    @PostMapping("/api/rider/offers/{offerId}/view")
    public void viewOffer(Authentication auth, @PathVariable String offerId) {
        dispatch.markViewed(offerId, Actor.from(auth).userId());
    }

    @PostMapping("/api/rider/offers/{offerId}/accept")
    public DispatchService.AcceptResult accept(Authentication auth, @PathVariable String offerId) {
        return dispatch.accept(offerId, Actor.from(auth).userId());
    }

    public record RejectRequest(String reason) {}

    @PostMapping("/api/rider/offers/{offerId}/reject")
    public void reject(Authentication auth, @PathVariable String offerId, @RequestBody(required = false) RejectRequest req) {
        dispatch.reject(offerId, Actor.from(auth).userId(), req == null ? null : req.reason());
    }

    @GetMapping("/api/rider/deliveries/{deliveryId}")
    @Transactional(readOnly = true)
    public TrackingViews.RiderDelivery delivery(Authentication auth, @PathVariable String deliveryId) {
        Delivery d = deliveries.findById(deliveryId).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", deliveryId));
        if (!Actor.from(auth).userId().equals(d.getRiderId())) throw ApiException.forbidden("NOT_ASSIGNED_RIDER", "배정된 배송이 아닙니다");
        return query.riderView(d);
    }

    @PostMapping("/api/rider/deliveries/{deliveryId}/arrive-mfc")
    public TrackingViews.RiderDelivery arriveMfc(Authentication auth, @PathVariable String deliveryId) {
        execution.arriveAtMfc(deliveryId, Actor.from(auth).userId());
        return delivery(auth, deliveryId);
    }

    public record ScanRequest(@NotBlank String qrCode) {}

    @PostMapping("/api/rider/deliveries/{deliveryId}/handover-scans")
    public HandoverService.ScanResult scan(Authentication auth, @PathVariable String deliveryId, @Valid @RequestBody ScanRequest req) {
        return handover.scan(deliveryId, Actor.from(auth).userId(), req.qrCode());
    }

    @PostMapping("/api/rider/deliveries/{deliveryId}/start")
    public TrackingViews.RiderDelivery start(Authentication auth, @PathVariable String deliveryId) {
        execution.startDelivery(deliveryId, Actor.from(auth).userId());
        return delivery(auth, deliveryId);
    }

    @PostMapping("/api/rider/deliveries/{deliveryId}/arrive-destination")
    public TrackingViews.RiderDelivery arriveDestination(Authentication auth, @PathVariable String deliveryId) {
        execution.arriveAtDestination(deliveryId, Actor.from(auth).userId());
        return delivery(auth, deliveryId);
    }

    @PostMapping("/api/rider/deliveries/{deliveryId}/release")
    public void release(Authentication auth, @PathVariable String deliveryId, @RequestBody(required = false) RejectRequest req) {
        execution.releaseByRider(deliveryId, Actor.from(auth).userId(), req == null ? null : req.reason());
    }

    public record LocationBatch(@NotNull List<LocationPoint> points) {}

    /**
     * 실 GPS 위치 보고. 출처는 이 경로에서 DEVICE_GPS 로 고정되며, SIMULATED_ROUTE 배송에는 거부된다(앱이 source 를 주장할 수 없음).
     */
    @PostMapping("/api/rider/deliveries/{deliveryId}/locations")
    public LocationIngestService.IngestResult locations(Authentication auth, @PathVariable String deliveryId, @Valid @RequestBody LocationBatch batch) {
        return ingest.ingest(new IngestContext(deliveryId, Actor.from(auth).userId(), LocationMode.DEVICE_GPS, null, null, null), batch.points());
    }

    @PostMapping(value = "/api/rider/deliveries/{deliveryId}/complete", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExecutionService.CompleteResult complete(Authentication auth, @PathVariable String deliveryId,
                                                    @RequestParam("requestId") String requestId,
                                                    @RequestParam("method") ProofMethod method,
                                                    @RequestParam(value = "note", required = false) String note,
                                                    @RequestParam(value = "photo", required = false) MultipartFile photo) throws IOException {
        byte[] bytes = photo == null ? null : photo.getBytes();
        return execution.complete(deliveryId, Actor.from(auth).userId(), requestId, method, bytes, photo == null ? null : photo.getContentType(), note);
    }
}
