package com.baropick.delivery.testsupport.simulation;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.testsupport.admin.AdminGuard;

/**
 * SIMULATED_ROUTE 제어 API (local-test + delivery.simulation.enabled=true 일 때만 존재). 테스트 관리자 전용.
 */
@RestController
@Profile("local-test")
@ConditionalOnProperty(name = "delivery.simulation.enabled", havingValue = "true")
public class SimulationController {
    private final SimulationService sim;

    public SimulationController(SimulationService sim) { this.sim = sim; }

    @GetMapping("/test-api/simulations/fixtures")
    public List<RouteProviders.Fixture> fixtures(Authentication auth) {
        AdminGuard.require(auth);
        return sim.fixtures();
    }

    @GetMapping("/test-api/simulations")
    public Collection<SimulationService.Status> list(Authentication auth) {
        AdminGuard.require(auth);
        return sim.list();
    }

    @PostMapping("/test-api/simulations")
    public SimulationService.Status create(Authentication auth, @RequestBody SimulationService.CreateRequest req) {
        var a = AdminGuard.require(auth);
        try {
            return sim.create(req, a.label());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_SCENARIO", e.getMessage());
        }
    }

    @GetMapping("/test-api/simulations/{id}")
    public SimulationService.Status get(Authentication auth, @PathVariable String id) {
        AdminGuard.require(auth);
        return sim.status(id);
    }

    @PostMapping("/test-api/simulations/{id}/prepare")
    public SimulationService.Status prepare(Authentication auth, @PathVariable String id) {
        AdminGuard.require(auth);
        return sim.prepare(id);
    }

    @GetMapping("/test-api/simulations/{id}/route")
    public SimulationService.RoutePreview route(Authentication auth, @PathVariable String id) {
        AdminGuard.require(auth);
        return sim.route(id);
    }

    @PostMapping("/test-api/simulations/{id}/start")
    public SimulationService.Status start(Authentication auth, @PathVariable String id) {
        AdminGuard.require(auth);
        return sim.start(id);
    }

    @PostMapping("/test-api/simulations/{id}/pause")
    public SimulationService.Status pause(Authentication auth, @PathVariable String id) {
        AdminGuard.require(auth);
        return sim.pause(id);
    }

    @PostMapping("/test-api/simulations/{id}/resume")
    public SimulationService.Status resume(Authentication auth, @PathVariable String id) {
        AdminGuard.require(auth);
        return sim.resume(id);
    }

    @PostMapping("/test-api/simulations/{id}/stop")
    public SimulationService.Status stop(Authentication auth, @PathVariable String id) {
        var a = AdminGuard.require(auth);
        return sim.stop(id, "수동 종료 by " + a.label());
    }

    @PostMapping("/test-api/simulations/{id}/reset")
    public SimulationService.Status reset(Authentication auth, @PathVariable String id) {
        AdminGuard.require(auth);
        return sim.reset(id);
    }

    public record RateRequest(Integer rate) {}

    @PutMapping("/test-api/simulations/{id}/rate")
    public SimulationService.Status rate(Authentication auth, @PathVariable String id, @RequestBody RateRequest req) {
        AdminGuard.require(auth);
        if (req.rate() == null) throw ApiException.badRequest("INVALID_RATE", "rate 필요");
        return sim.setRate(id, req.rate());
    }

    public record FaultRequest(FaultSpec.Type type, Integer durationTicks, Integer durationSeconds) {}

    @PostMapping("/test-api/simulations/{id}/faults")
    public SimulationService.Status fault(Authentication auth, @PathVariable String id, @RequestBody FaultRequest req) {
        AdminGuard.require(auth);
        try {
            return sim.injectFault(id, req.type(), req.durationTicks(), req.durationSeconds());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_FAULT", e.getMessage());
        }
    }

    @DeleteMapping("/test-api/simulations/{id}")
    public Map<String, String> delete(Authentication auth, @PathVariable String id) {
        AdminGuard.require(auth);
        sim.delete(id);
        return Map.of("result", "DELETED");
    }
}
