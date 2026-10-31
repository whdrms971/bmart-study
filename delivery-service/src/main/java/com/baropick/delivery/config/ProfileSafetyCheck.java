package com.baropick.delivery.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 운영(prod) 프로필과 테스트 지원(local-test) 프로필 혼합 기동을 막는다 (D-33). */
@Component
public class ProfileSafetyCheck {
    public ProfileSafetyCheck(Environment env, DeliveryProperties props) {
        if (env.matchesProfiles("prod") && env.matchesProfiles("local-test")) {
            throw new IllegalStateException("prod 와 local-test 프로필을 동시에 활성화할 수 없습니다");
        }
        if (env.matchesProfiles("prod") && props.simulation().enabled()) {
            throw new IllegalStateException("prod 프로필에서는 delivery.simulation.enabled=true 를 허용하지 않습니다");
        }
    }
}
