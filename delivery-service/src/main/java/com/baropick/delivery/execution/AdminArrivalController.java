package com.baropick.delivery.execution;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baropick.delivery.auth.Actor;
import com.baropick.delivery.domain.ArrivalException;

/**
 * 관리자 고객 도착 예외 처리. 운영 기능이므로 모든 프로필에 존재하며 /api/admin/** 는 ADMIN 역할만 접근한다(SecurityConfig).
 */
@RestController
public class AdminArrivalController {

    public record DecisionRequest(String note) {}

    private final ArrivalExceptionService service;

    public AdminArrivalController(ArrivalExceptionService service) { this.service = service; }

    @GetMapping("/api/admin/arrival-exceptions")
    public List<ArrivalExceptionService.View> list(@RequestParam(required = false) ArrivalException.Status status) {
        return service.list(status);
    }

    @PostMapping("/api/admin/arrival-exceptions/{id}/approve")
    public ArrivalExceptionService.View approve(Authentication auth, @PathVariable String id, @RequestBody(required = false) DecisionRequest req) {
        return service.approve(id, Actor.from(auth).userId(), req == null ? null : req.note());
    }

    @PostMapping("/api/admin/arrival-exceptions/{id}/reject")
    public ArrivalExceptionService.View reject(Authentication auth, @PathVariable String id, @RequestBody(required = false) DecisionRequest req) {
        return service.reject(id, Actor.from(auth).userId(), req == null ? null : req.note());
    }
}
