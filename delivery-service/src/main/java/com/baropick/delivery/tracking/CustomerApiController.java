package com.baropick.delivery.tracking;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baropick.delivery.auth.Actor;
import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.kakao.KakaoClient;

@RestController
public class CustomerApiController {

    private final TrackingQueryService query;
    private final KakaoClient kakao;
    private final PlaceSearchRateLimiter placeLimiter;

    public CustomerApiController(TrackingQueryService query, KakaoClient kakao, PlaceSearchRateLimiter placeLimiter) {
        this.query = query;
        this.kakao = kakao;
        this.placeLimiter = placeLimiter;
    }

    /** 고객 본인 주문의 배송(분할 시 여러 건) 추적. */
    @GetMapping("/api/customer/orders/{orderId}/tracking")
    public TrackingViews.OrderTracking tracking(Authentication auth, @PathVariable String orderId) {
        return query.orderTrackingForCustomer(orderId, Actor.from(auth).userId());
    }

    /**
     * 배송지 주소 검색 (카카오 Local REST, 서버 키 사용).
     * 주소록 정본은 Commerce 이며, 이 엔드포인트는 테스트 주문용 좌표 조회 어댑터다.
     */
    @GetMapping("/api/customer/places")
    public List<KakaoClient.Place> places(Authentication auth, @RequestParam String query) {
        if (query == null || query.isBlank() || query.length() > 100) throw ApiException.badRequest("INVALID_QUERY", "검색어를 확인하세요");
        placeLimiter.acquire(Actor.from(auth).userId()); // SEC-03 분당 10회 (카카오 호출 전에 판정)
        try {
            return kakao.searchPlaces(query.trim());
        } catch (KakaoClient.KakaoApiException e) {
            throw ApiException.unavailable(e.code(), e.getMessage());
        }
    }
}
