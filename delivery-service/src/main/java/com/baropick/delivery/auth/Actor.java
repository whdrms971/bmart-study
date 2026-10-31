package com.baropick.delivery.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import com.baropick.delivery.common.ApiException;

/** 인증된 호출 주체. userId 는 JWT subject (서버 서명), 클라이언트 본문 값은 사용하지 않는다. */
public record Actor(String userId, Role role) {

    public static Actor from(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            if (auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL"))) {
                return new Actor("internal", Role.INTERNAL);
            }
            throw ApiException.forbidden("UNAUTHENTICATED", "인증이 필요합니다");
        }
        return new Actor(jwt.getSubject(), Role.valueOf(jwt.getClaimAsString("role")));
    }

    public String label() { return role.name() + ":" + userId; }
}
