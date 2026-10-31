package com.baropick.delivery.tracking;

/** 라이더에게 제안 생성/종료를 알리는 커밋 후 이벤트. */
public record OfferChanged(String riderId, String offerId, String deliveryId, String status) {}
