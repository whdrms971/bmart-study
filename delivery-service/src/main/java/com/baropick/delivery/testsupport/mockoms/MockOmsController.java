package com.baropick.delivery.testsupport.mockoms;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.baropick.delivery.auth.Actor;
import com.baropick.delivery.auth.Role;
import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.testsupport.admin.AdminGuard;

@RestController
@Profile("local-test")
public class MockOmsController {
    private final MockOmsService oms;

    public MockOmsController(MockOmsService oms) { this.oms = oms; }

    /** 고객 앱/관리자 UI 의 "테스트 주문". 고객은 본인 명의로만, 관리자는 customerId 지정 가능. */
    public record CreateOrder(String customerId, MockOmsService.TestOrderRequest order) {}

    @PostMapping("/test-api/mock-oms/orders")
    public MockOmsService.OrderView create(Authentication auth, @RequestBody CreateOrder req) {
        Actor a = Actor.from(auth);
        String customerId = switch (a.role()) {
            case CUSTOMER -> a.userId();
            case ADMIN -> {
                if (req.customerId() == null || req.customerId().isBlank()) throw ApiException.badRequest("CUSTOMER_REQUIRED", "customerId 필요");
                yield req.customerId();
            }
            default -> throw ApiException.forbidden("FORBIDDEN", "주문 생성 권한 없음");
        };
        return oms.createTestOrder(customerId, req.order());
    }

    @GetMapping("/test-api/mock-oms/orders")
    public List<MockOmsService.OrderView> list(Authentication auth) {
        Actor a = Actor.from(auth);
        if (a.role() == Role.CUSTOMER) return oms.orders(a.userId());
        AdminGuard.require(auth);
        return oms.orders(null);
    }

    @GetMapping("/test-api/mock-oms/orders/{orderId}")
    public MockOmsService.OrderView get(Authentication auth, @PathVariable String orderId) {
        Actor a = Actor.from(auth);
        var o = oms.order(orderId);
        if (a.role() == Role.CUSTOMER && !o.customerId().equals(a.userId())) throw ApiException.notFound("ORDER_NOT_FOUND", "주문 없음");
        if (a.role() == Role.RIDER) throw ApiException.forbidden("FORBIDDEN", "권한 없음");
        return o;
    }

    @PostMapping("/test-api/mock-oms/orders/{orderId}/cancel")
    public Map<String, Object> cancel(Authentication auth, @PathVariable String orderId) {
        Actor a = Actor.from(auth);
        if (a.role() == Role.CUSTOMER) return oms.cancelOrder(orderId, a.userId());
        AdminGuard.require(auth);
        return oms.cancelOrder(orderId, null);
    }

    @PostMapping("/test-api/mock-oms/deliveries/{deliveryId}/resend-request")
    public Map<?, ?> resend(Authentication auth, @PathVariable String deliveryId) {
        AdminGuard.require(auth);
        return oms.resendDeliveryRequest(deliveryId);
    }

    @GetMapping("/test-api/mock-oms/inbox")
    public List<Map<String, Object>> inbox(Authentication auth) {
        AdminGuard.require(auth);
        return oms.inbox();
    }

    public record EventFaultRequest(MockOmsService.EventFault fault, Integer times) {}

    @PutMapping("/test-api/mock-oms/event-fault")
    public Map<String, Object> fault(Authentication auth, @RequestBody EventFaultRequest req) {
        AdminGuard.require(auth);
        oms.setEventFault(req.fault(), req.times() == null ? 1 : req.times());
        return Map.of("fault", oms.eventFault().name());
    }

    /** Delivery outbox → OMS 이벤트 수신 (내부 토큰). */
    @PostMapping("/mock-oms/internal/events")
    public Map<String, String> event(@RequestBody String raw) {
        return Map.of("result", oms.onEvent(raw));
    }
}
