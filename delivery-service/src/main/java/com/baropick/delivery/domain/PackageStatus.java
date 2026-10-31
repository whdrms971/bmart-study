package com.baropick.delivery.domain;

/** Delivery 관점의 포장 상태. READY=MFC 포장 완료 통지, HANDED_OVER=MFC 인계 확정+라이더 스캔. */
public enum PackageStatus { READY, HANDOVER_UNKNOWN, HANDED_OVER, VOIDED }
