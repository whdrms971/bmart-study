package com.baropick.delivery.tracking;

/** 커밋 후 실시간 전파를 위한 도메인 내부 이벤트. */
public record DeliveryChanged(String deliveryId, String kind, String previousRiderId) {
    public static DeliveryChanged of(String deliveryId, String kind) { return new DeliveryChanged(deliveryId, kind, null); }
}
