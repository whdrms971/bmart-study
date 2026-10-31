package com.baropick.delivery.testsupport.admin;

import org.springframework.security.core.Authentication;

import com.baropick.delivery.auth.Actor;
import com.baropick.delivery.auth.Role;
import com.baropick.delivery.common.ApiException;

public final class AdminGuard {
    private AdminGuard() {}

    public static Actor require(Authentication auth) {
        Actor a = Actor.from(auth);
        if (a.role() != Role.ADMIN) throw ApiException.forbidden("ADMIN_ONLY", "테스트 관리자 권한이 필요합니다");
        return a;
    }
}
