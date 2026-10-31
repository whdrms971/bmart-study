package com.baropick.delivery.tracking;

import java.time.Instant;
import java.util.List;

import com.baropick.delivery.domain.DeliveryStatus;

/** 외부 응답 DTO (엔티티를 직접 노출하지 않는다). */
public final class TrackingViews {
    private TrackingViews() {}

    public record Point(double latitude, double longitude) {}

    public record Destination(double latitude, double longitude, String address, String addressDetail) {}

    public record RiderPosition(double latitude, double longitude, Double accuracyMeters, Instant measuredAt, Instant receivedAt,
                                String source, long ageSeconds) {}

    public record Eta(String status, Integer etaSeconds, Integer distanceMeters, Instant computedAt, Instant estimatedArrivalAt,
                      String provider, String message) {}

    public record PackageSummary(int total, int handedOver) {}

    /** 고객용: 본인 배송지·본인 배송의 라이더 위치만. 다른 고객 정보·라이더 전체 경로 미포함. */
    public record CustomerDelivery(String deliveryId, String orderId, DeliveryStatus status, String statusLabel, String locationMode,
                                   boolean simulated, Destination destination, boolean riderLocationVisible, RiderPosition rider,
                                   Eta eta, PackageSummary packages, Instant updatedAt, Instant pickedUpAt, Instant deliveredAt,
                                   Instant cancelledAt, Instant serverTime) {}

    public record OrderTracking(String orderId, List<CustomerDelivery> deliveries, Instant serverTime) {}

    public record PackageView(String packageId, String status, Instant handedOverAt, String lastError) {}

    public record Mfc(String mfcId, String name, boolean virtualSite, double latitude, double longitude) {}

    public record RiderDelivery(String deliveryId, String orderId, DeliveryStatus status, String statusLabel, String locationMode,
                                boolean simulated, Mfc mfc, Destination destination, String customerNote, int expectedPackageCount,
                                boolean packagesReady, List<PackageView> packages, RiderPosition lastLocation, Eta eta,
                                int assignmentVersion, Instant updatedAt) {}

    public record Offer(String offerId, String deliveryId, String orderId, String status, Instant offeredAt, Instant expiresAt,
                        long remainingSeconds, Mfc mfc, String destinationAddress, double straightDistanceMeters,
                        int expectedPackageCount, Integer totalWeightGrams, String locationMode) {}

    public static String label(DeliveryStatus s) {
        return switch (s) {
            case REQUESTED -> "배차 대기";
            case OFFERING -> "라이더 배정 중";
            case ASSIGNED -> "라이더 배정 완료";
            case ARRIVED_AT_MFC -> "라이더 픽업지 도착";
            case PICKED_UP -> "상품 픽업 완료";
            case IN_TRANSIT -> "배송 중";
            case ARRIVED_AT_DESTINATION -> "배송지 도착";
            case DELIVERED -> "배송 완료";
            case CANCELLED -> "배송 취소";
        };
    }
}
