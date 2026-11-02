package com.baropick.delivery.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Geo;
import com.baropick.delivery.common.Hashing;
import com.baropick.delivery.common.IdempotencyService;
import com.baropick.delivery.common.Ids;
import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.config.DeliveryProperties.Policy;
import com.baropick.delivery.dispatch.DispatchService;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryPackage;
import com.baropick.delivery.domain.DeliveryPackageRepository;
import com.baropick.delivery.domain.DeliveryProof;
import com.baropick.delivery.domain.DeliveryProofRepository;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.LatestLocationRepository;
import com.baropick.delivery.domain.PackageStatus;
import com.baropick.delivery.domain.ProofMethod;
import com.baropick.delivery.intake.InternalDeliveryController.CancelRequest;
import com.baropick.delivery.outbox.OutboxService;
import com.baropick.delivery.tracking.DeliveryChanged;

/**
 * 배송 수행 상태 전이: MFC 도착 → (인계: HandoverService) → 배송 출발 → 고객 도착 → 증빙 완료.
 * GPS 도착·시간 경과·시뮬레이터 종료만으로 완료하지 않는다. 완료는 라이더 요청 + 사진 증빙 검증 시에만.
 */
@Service
public class ExecutionService {
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final DeliveryRepository deliveries;
    private final DeliveryPackageRepository packages;
    private final DeliveryProofRepository proofs;
    private final LatestLocationRepository latest;
    private final DispatchService dispatch;
    private final HistoryRecorder history;
    private final OutboxService outbox;
    private final IdempotencyService idempotency;
    private final ApplicationEventPublisher events;
    private final Policy policy;
    private final ArrivalPolicy arrival;
    private final Clock clock;

    public ExecutionService(DeliveryRepository deliveries, DeliveryPackageRepository packages, DeliveryProofRepository proofs,
                            LatestLocationRepository latest, DispatchService dispatch, HistoryRecorder history, OutboxService outbox,
                            IdempotencyService idempotency, ApplicationEventPublisher events, DeliveryProperties props,
                            ArrivalPolicy arrival, Clock clock) {
        this.arrival = arrival;
        this.deliveries = deliveries;
        this.packages = packages;
        this.proofs = proofs;
        this.latest = latest;
        this.dispatch = dispatch;
        this.history = history;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.events = events;
        this.policy = props.policy();
        this.clock = clock;
    }

    private Delivery lockOwned(String deliveryId, String riderId) {
        Delivery d = deliveries.findForUpdate(deliveryId).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", deliveryId));
        if (!riderId.equals(d.getRiderId())) throw ApiException.forbidden("NOT_ASSIGNED_RIDER", "이 배송에 배정된 라이더가 아닙니다");
        return d;
    }

    @Transactional
    public Delivery arriveAtMfc(String deliveryId, String riderId) {
        Delivery d = lockOwned(deliveryId, riderId);
        if (d.getStatus() == DeliveryStatus.ARRIVED_AT_MFC) return d;
        DeliveryStatus from = d.transition(DeliveryStatus.ARRIVED_AT_MFC, clock.instant(), DeliveryStatus.ASSIGNED);
        history.record(d, from, "RIDER:" + riderId, "MFC 도착 (라이더 입력)");
        return d;
    }

    @Transactional
    public Delivery startDelivery(String deliveryId, String riderId) {
        Delivery d = lockOwned(deliveryId, riderId);
        if (d.getStatus() == DeliveryStatus.IN_TRANSIT) return d;
        if (d.getStatus().atOrAfter(DeliveryStatus.ASSIGNED) && !d.getStatus().atOrAfter(DeliveryStatus.PICKED_UP)) {
            throw ApiException.conflict("HANDOVER_NOT_COMPLETED", "모든 포장의 인계가 확정되기 전에는 배송을 시작할 수 없습니다");
        }
        DeliveryStatus from = d.transition(DeliveryStatus.IN_TRANSIT, clock.instant(), DeliveryStatus.PICKED_UP);
        history.record(d, from, "RIDER:" + riderId, "배송 출발");
        return d;
    }

    /**
     * 고객 도착 (DECISIONS.md A-01~A-04). 순서: 담당 라이더 → 이미 도착이면 멱등 → 배송 중(IN_TRANSIT)만 허용 → 위치·거리 판정.
     * 거리 초과·위치 불량은 거부되며, 라이더가 제한을 해제할 수 없다(예외는 관리자 승인 경로 ArrivalExceptionService).
     */
    @Transactional
    public Delivery arriveAtDestination(String deliveryId, String riderId) {
        Delivery d = lockOwned(deliveryId, riderId);
        if (d.getStatus() == DeliveryStatus.ARRIVED_AT_DESTINATION) return d;
        if (d.getStatus() != DeliveryStatus.IN_TRANSIT) {
            throw ApiException.conflict("INVALID_DELIVERY_STATUS", "배송 중(IN_TRANSIT)인 배송만 고객 도착 처리할 수 있습니다 (현재 " + d.getStatus() + ")");
        }
        ArrivalPolicy.Check c = arrival.evaluate(d);
        if (!c.allowed()) throw arrival.toException(c);
        DeliveryStatus from = d.transition(DeliveryStatus.ARRIVED_AT_DESTINATION, clock.instant(), DeliveryStatus.IN_TRANSIT);
        history.record(d, from, "RIDER:" + riderId, String.format("고객 도착 (라이더 입력, 거리 판정 통과 — %s, 허용 %.0fm)",
                c.summary(), c.maxDistanceMeters()));
        return d;
    }

    public record CompleteResult(String deliveryId, String result, String proofId, Instant completedAt) {}

    /** 전달 완료. 같은 requestId 재요청은 같은 결과(D-13). 다른 requestId 로 재완료 시 409. */
    @Transactional
    public CompleteResult complete(String deliveryId, String riderId, String requestId, ProofMethod method, byte[] photo,
                                   String contentType, String note) {
        if (requestId == null || requestId.isBlank()) throw ApiException.badRequest("REQUEST_ID_REQUIRED", "requestId 가 필요합니다");
        if (method == null) throw ApiException.badRequest("PROOF_METHOD_REQUIRED", "전달 방식(대면/비대면)이 필요합니다");
        if (requestId.length() > 100) throw ApiException.badRequest("REQUEST_ID_TOO_LONG", "requestId 는 100자 이하");
        if (note != null && note.length() > 300) throw ApiException.badRequest("NOTE_TOO_LONG", "메모는 300자 이하");
        Delivery d = lockOwnedOrPrevious(deliveryId, riderId);
        var prior = proofs.findByDeliveryId(deliveryId);
        if (prior.isPresent()) {
            if (prior.get().getRequestId().equals(requestId) && prior.get().getRiderId().equals(riderId)) {
                return new CompleteResult(deliveryId, "ALREADY_COMPLETED", prior.get().getId(), d.getDeliveredAt());
            }
            throw ApiException.conflict("ALREADY_COMPLETED", "이미 완료 처리된 배송입니다");
        }
        if (!riderId.equals(d.getRiderId())) throw ApiException.forbidden("NOT_ASSIGNED_RIDER", "이 배송에 배정된 라이더가 아닙니다");
        if (d.getStatus() != DeliveryStatus.ARRIVED_AT_DESTINATION) {
            throw ApiException.conflict("INVALID_STATE", "고객 도착 처리 후에만 전달 완료할 수 있습니다 (현재 " + d.getStatus() + ")");
        }
        if (photo == null || photo.length == 0) throw ApiException.badRequest("PROOF_PHOTO_REQUIRED", "전달 사진 증빙이 필요합니다");
        // 클라이언트가 보낸 Content-Type 은 신뢰하지 않고 파일 시그니처로 판정한다
        String detected = detectImageType(photo);
        if (detected == null || !IMAGE_TYPES.contains(detected)) {
            throw ApiException.badRequest("PROOF_PHOTO_INVALID", "JPEG/PNG/WEBP 이미지만 허용됩니다");
        }
        contentType = detected;
        Instant now = clock.instant();
        String proofId = Ids.next("PRF");
        String dir = Policy.require(policy.proofStorageDir(), "proof-storage-dir");
        Path base = Path.of(dir).toAbsolutePath().normalize();
        Path path = base.resolve(deliveryId).resolve(proofId + ext(contentType)).normalize();
        // deliveryId 는 입구(ID_PATTERN)에서 검증되지만, 저장 경로가 증빙 폴더를 벗어나지 않음을 한 번 더 확인한다
        if (!path.startsWith(base) || !path.getParent().getParent().equals(base)) {
            throw ApiException.badRequest("INVALID_DELIVERY_ID", "배송 ID 형식 오류");
        }
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, photo);
        } catch (IOException e) {
            throw ApiException.unavailable("PROOF_STORAGE_FAILED", "증빙 사진 저장에 실패했습니다. 다시 시도하세요");
        }
        Double dist = latest.findById(deliveryId).map(l -> Geo.distanceMeters(l.getLat(), l.getLng(), d.getDestLat(), d.getDestLng())).orElse(null);
        proofs.save(new DeliveryProof(proofId, deliveryId, riderId, requestId, method, path.toString(), Hashing.sha256(photo), photo.length,
                note, dist, now));
        DeliveryStatus from = d.transition(DeliveryStatus.DELIVERED, now, DeliveryStatus.ARRIVED_AT_DESTINATION);
        d.markDelivered(now);
        history.record(d, from, "RIDER:" + riderId, "전달 완료 (" + method + ", 증빙 " + proofId + ")");
        outbox.append("DeliveryCompleted", d.getId(), Map.of(
                "deliveryId", d.getId(), "orderId", d.getOrderId(), "riderId", riderId, "completedAt", now.toString(),
                "proofId", proofId, "deliveryMethod", method.name()));
        return new CompleteResult(deliveryId, "COMPLETED", proofId, now);
    }

    /** 완료 후 같은 요청 재전송을 위해 배정 검사 없이 잠금. */
    private Delivery lockOwnedOrPrevious(String deliveryId, String riderId) {
        return deliveries.findForUpdate(deliveryId).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", deliveryId));
    }

    private static String detectImageType(byte[] b) {
        if (b.length < 12) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    private static String ext(String ct) {
        return switch (ct) { case "image/png" -> ".png"; case "image/webp" -> ".webp"; default -> ".jpg"; };
    }

    /** 라이더 배차취소(픽업 전만). 픽업/인계 진행 후는 지원센터 처리 필요 (DECISIONS P-11). */
    @Transactional
    public Delivery releaseByRider(String deliveryId, String riderId, String reason) {
        Delivery d = lockOwned(deliveryId, riderId);
        return unassign(d, "RIDER:" + riderId, "라이더 배차취소: " + (reason == null ? "사유 미입력" : reason));
    }

    /** 관리자 재배정: 현재 라이더 권한 회수 후 재배차 (D-17). */
    @Transactional
    public Delivery reassignByAdmin(String deliveryId, String adminId, String reason) {
        Delivery d = deliveries.findForUpdate(deliveryId).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", deliveryId));
        if (d.getRiderId() == null) throw ApiException.conflict("NO_RIDER_ASSIGNED", "배정된 라이더가 없습니다");
        return unassign(d, "ADMIN:" + adminId, "관리자 재배정: " + (reason == null ? "사유 미입력" : reason));
    }

    private Delivery unassign(Delivery d, String actor, String reason) {
        if (!DeliveryStatus.BEFORE_PICKUP.contains(d.getStatus()) || anyPackageMoved(d.getId())) {
            throw ApiException.conflict("SUPPORT_REQUIRED", "인계(픽업) 이후 배정 해제는 지원센터 처리(상품 위치·회수 확인)가 필요합니다");
        }
        Instant now = clock.instant();
        String prev = d.getRiderId();
        d.unassignRider(now);
        DeliveryStatus from = d.transition(DeliveryStatus.REQUESTED, now, DeliveryStatus.ASSIGNED, DeliveryStatus.ARRIVED_AT_MFC);
        history.record(d, from, actor, reason + " (배정버전 " + d.getAssignmentVersion() + ")");
        events.publishEvent(new DeliveryChanged(d.getId(), "ASSIGNMENT_REVOKED", prev));
        return d;
    }

    private boolean anyPackageHandedOver(String deliveryId) {
        return packages.findByDeliveryIdOrderByPackageId(deliveryId).stream().anyMatch(p -> p.getStatus() == PackageStatus.HANDED_OVER);
    }

    /** 배정 해제(재배정·라이더 배차취소)용: MFC 판정 없이 진행되므로 인계 처리 중·응답 불명도 "이동 가능성 있음"으로 막는다. */
    private boolean anyPackageMoved(String deliveryId) {
        List<DeliveryPackage> ps = packages.findByDeliveryIdOrderByPackageId(deliveryId);
        return ps.stream().anyMatch(p -> p.getStatus() == PackageStatus.HANDED_OVER || p.getStatus() == PackageStatus.HANDOVER_UNKNOWN);
    }

    public record CancelResult(String deliveryId, String result, DeliveryStatus status, String message) {}

    /**
     * OMS 주문 취소에 따른 배송 취소. MFC 출고 중지 확정 후 호출되는 것이 원칙(cancel-vs-handover S3-b).
     * 인계가 하나라도 확정·불명이면 단순 취소 불가 → 회수 경로.
     */
    @Transactional
    public CancelResult cancelFromOms(String deliveryId, CancelRequest req) {
        Delivery peek = deliveries.findById(deliveryId).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", deliveryId));
        // (2026-11-02) 이전의 "인계 결과 불명이면 409 HANDOVER_CONFIRMING" 사전 검사는 제거했다. 취소 요청은 MFC 가 전 포장
        // STOPPED 를 확정한 뒤에만 오므로(S3-b) MFC 판정이 Delivery 의 불명·처리 중 상태보다 우선한다. 인계를 트랜잭션 밖 MFC
        // 호출로 바꾼 뒤 이 검사가 정상 취소를 막았다(D-12).
        return idempotency.execute(req.sourceSystem(), "cancel:" + req.requestId(), Map.of("deliveryId", peek.getId(), "reason",
                String.valueOf(req.reason())), CancelResult.class, () -> doCancel(deliveryId, req));
    }

    private CancelResult doCancel(String deliveryId, CancelRequest req) {
        Delivery d = deliveries.findForUpdate(deliveryId).orElseThrow();
        if (d.getStatus() == DeliveryStatus.CANCELLED) return new CancelResult(deliveryId, "ALREADY_CANCELLED", d.getStatus(), "이미 취소됨");
        if (d.getStatus() == DeliveryStatus.DELIVERED) {
            return new CancelResult(deliveryId, "NOT_CANCELLABLE_DELIVERED", d.getStatus(), "이미 전달 완료됨 — 반품 절차 대상");
        }
        // OMS 는 MFC 출고 중지(StopWork)에서 전 포장 STOPPED 를 받은 배송만 취소를 요청한다(cancel-vs-handover S3-b).
        // 따라서 Delivery 쪽 "인계 처리 중/응답 불명" 포장은 MFC 판정상 인계되지 않았다 → 실제 인계 확정 포장만 취소를 막는다.
        if (!DeliveryStatus.BEFORE_PICKUP.contains(d.getStatus()) || anyPackageHandedOver(deliveryId)) {
            history.note(d, req.sourceSystem(), "취소 요청 거절: 인계 확정 포장 존재 → 회수 필요");
            return new CancelResult(deliveryId, "REJECTED_HANDED_OVER", d.getStatus(), "인계된 포장이 있어 출고 전 취소 불가 — 회수·반품 경로");
        }
        Instant now = clock.instant();
        String prev = d.getRiderId();
        dispatch.cancelOpenOffers(d, "주문 취소", now);
        if (prev != null) d.unassignRider(now);
        for (DeliveryPackage p : packages.findByDeliveryIdOrderByPackageId(deliveryId)) p.voided(now);
        DeliveryStatus from = d.transition(DeliveryStatus.CANCELLED, now, DeliveryStatus.REQUESTED, DeliveryStatus.OFFERING,
                DeliveryStatus.ASSIGNED, DeliveryStatus.ARRIVED_AT_MFC);
        d.markCancelled(now);
        history.record(d, from, req.sourceSystem(), "주문 취소에 따른 배송 취소: " + req.reason());
        if (prev != null) events.publishEvent(new DeliveryChanged(d.getId(), "ASSIGNMENT_REVOKED", prev));
        outbox.append("DeliveryCancelled", d.getId(), Map.of("deliveryId", d.getId(), "orderId", d.getOrderId(),
                "cancelledAt", now.toString(), "beforePickup", true));
        return new CancelResult(deliveryId, "CANCELLED", d.getStatus(), "픽업 전 취소 완료");
    }
}
