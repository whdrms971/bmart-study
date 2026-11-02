package com.baropick.delivery.intake;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Geo;
import com.baropick.delivery.common.Hashing;
import com.baropick.delivery.common.Ids;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.StatusHistory;
import com.baropick.delivery.domain.StatusHistoryRepository;
import com.baropick.delivery.mfc.MfcRegistry;
import com.baropick.delivery.tracking.DeliveryChanged;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

/**
 * OMS → Delivery 배송 작업 요청. (sourceSystem, requestId) 멱등:
 * 같은 키·같은 본문 → 기존 deliveryId 반환(created=false), 같은 키·다른 본문 → 409.
 * 위치 모드는 요청 본문이 아니라 MFC 설정으로 서버가 고정한다.
 */
@Service
public class DeliveryRequestService {

    /**
     * 식별자 형식: 영문·숫자·'_'·'-' 1~40자. deliveryId 는 증빙 사진 저장 경로에, orderId·riderId 는 STOMP 토픽에 쓰이므로
     * 경로 구분자·'..' 등이 들어오지 못하게 입구에서 막는다(WebSocketConfig 토픽 패턴과 같은 규칙).
     */
    public static final String ID_PATTERN = "^[A-Za-z0-9_-]{1,40}$";

    public record Destination(@NotNull Double latitude, @NotNull Double longitude, @NotBlank @Size(max = 300) String address,
                              @Size(max = 200) String addressDetail) {}

    public record DeliveryRequest(
            @NotBlank @Pattern(regexp = ID_PATTERN) String sourceSystem,
            @NotBlank @Size(max = 100) String requestId,
            @NotBlank @Pattern(regexp = ID_PATTERN) String orderId,
            @NotBlank @Pattern(regexp = ID_PATTERN) String deliveryId,
            @NotBlank @Pattern(regexp = ID_PATTERN) String customerId,
            @NotBlank @Pattern(regexp = ID_PATTERN) String mfcId,
            @NotNull @Valid Destination destination,
            @NotNull @Min(1) @Max(20) Integer expectedPackageCount,
            @Min(0) Integer totalWeightGrams,
            @Size(max = 300) String customerNote) {}

    public record DeliveryRequestResult(String deliveryId, String orderId, boolean created, DeliveryStatus status, String locationMode) {}

    private final DeliveryRepository deliveries;
    private final StatusHistoryRepository history;
    private final MfcRegistry mfcs;
    private final JsonMapper json;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public DeliveryRequestService(DeliveryRepository deliveries, StatusHistoryRepository history, MfcRegistry mfcs, JsonMapper json,
                                  Clock clock, ApplicationEventPublisher events) {
        this.deliveries = deliveries;
        this.history = history;
        this.mfcs = mfcs;
        this.json = json;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public DeliveryRequestResult accept(DeliveryRequest req) {
        if (!Geo.validLat(req.destination().latitude()) || !Geo.validLng(req.destination().longitude())) {
            throw ApiException.badRequest("INVALID_COORDINATE", "배송지 좌표 범위 오류");
        }
        String hash = Hashing.sha256(json.writeValueAsString(req));
        var existing = deliveries.findBySourceSystemAndRequestId(req.sourceSystem(), req.requestId());
        if (existing.isPresent()) return replay(existing.get(), hash);
        if (deliveries.existsById(req.deliveryId())) {
            throw ApiException.conflict("DELIVERY_ID_CONFLICT", "이미 다른 요청으로 생성된 deliveryId 입니다");
        }
        var site = mfcs.require(req.mfcId());
        Instant now = clock.instant();
        Delivery d = new Delivery(req.deliveryId(), req.orderId(), req.customerId(), req.mfcId(), req.sourceSystem(), req.requestId(), hash,
                site.locationMode(), req.destination().latitude(), req.destination().longitude(), req.destination().address(),
                req.destination().addressDetail(), site.latitude(), site.longitude(), req.totalWeightGrams(), req.expectedPackageCount(),
                req.customerNote(), now);
        try {
            deliveries.saveAndFlush(d);
        } catch (DataIntegrityViolationException e) {
            // 동시 동일 요청: 먼저 커밋된 쪽을 기준으로 판정
            throw ApiException.conflict("DUPLICATE_REQUEST_IN_FLIGHT", "같은 요청이 동시에 처리 중입니다. 잠시 후 다시 조회하세요");
        }
        history.save(new StatusHistory(d.getId(), null, d.getStatus(), req.sourceSystem(), "배송 요청 수신 (모드 " + site.locationMode() + ")", now));
        events.publishEvent(DeliveryChanged.of(d.getId(), "CREATED"));
        return new DeliveryRequestResult(d.getId(), d.getOrderId(), true, d.getStatus(), d.getLocationMode().name());
    }

    private DeliveryRequestResult replay(Delivery d, String hash) {
        if (!d.getRequestHash().equals(hash)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 requestId 로 다른 내용의 배송 요청이 이미 처리되었습니다");
        }
        return new DeliveryRequestResult(d.getId(), d.getOrderId(), false, d.getStatus(), d.getLocationMode().name());
    }

    public List<Delivery> byOrder(String orderId) { return deliveries.findByOrderIdOrderByCreatedAt(orderId); }

    public static String newDeliveryId() { return Ids.next("DLV"); }
}
