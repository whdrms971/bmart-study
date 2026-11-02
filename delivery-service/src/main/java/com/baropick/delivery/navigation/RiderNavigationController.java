package com.baropick.delivery.navigation;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baropick.delivery.auth.Actor;

/** 라이더 앱 안 길안내 경로. CONTRACTS.md "라이더 길안내" 절. */
@RestController
public class RiderNavigationController {
    private final RiderNavigationService navigation;

    public RiderNavigationController(RiderNavigationService navigation) { this.navigation = navigation; }

    @GetMapping("/api/rider/deliveries/{deliveryId}/navigation")
    public RiderNavigationService.NavigationView route(Authentication auth, @PathVariable String deliveryId,
                                                       @RequestParam RiderNavigationService.Target target,
                                                       @RequestParam(required = false) Double lat,
                                                       @RequestParam(required = false) Double lng) {
        return navigation.route(deliveryId, Actor.from(auth).userId(), target, lat, lng);
    }
}
