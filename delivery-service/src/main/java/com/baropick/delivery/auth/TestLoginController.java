package com.baropick.delivery.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.config.DeliveryProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * local-test 전용 테스트 계정 로그인. 운영 프로필에는 빈 자체가 없다(D-33).
 * 실제 Auth 서비스가 생기면 이 컨트롤러를 제거하고 IdP 토큰을 검증하도록 교체한다.
 */
@RestController
@Profile("local-test")
public class TestLoginController {

    private final DeliveryProperties.TestAccounts accounts;
    private final JwtService jwt;

    public TestLoginController(DeliveryProperties props, JwtService jwt) {
        this.accounts = props.testAccounts();
        this.jwt = jwt;
    }

    public record LoginRequest(@NotBlank String userId, @NotBlank String passcode) {}

    @PostMapping("/api/auth/test-login")
    public JwtService.IssuedToken login(@Valid @RequestBody LoginRequest req) {
        String pass = accounts.passcode();
        if (pass == null || pass.length() < 8) {
            throw ApiException.unavailable("TEST_LOGIN_DISABLED", "DELIVERY_TEST_PASSCODE(8자 이상)가 설정되지 않아 테스트 로그인이 비활성화되었습니다");
        }
        if (!MessageDigest.isEqual(pass.getBytes(StandardCharsets.UTF_8), req.passcode().getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.forbidden("INVALID_CREDENTIALS", "아이디 또는 패스코드가 올바르지 않습니다");
        }
        Role role;
        if (accounts.customers().contains(req.userId())) role = Role.CUSTOMER;
        else if (accounts.riders().contains(req.userId())) role = Role.RIDER;
        else if (accounts.admins().contains(req.userId())) role = Role.ADMIN;
        else throw ApiException.forbidden("INVALID_CREDENTIALS", "아이디 또는 패스코드가 올바르지 않습니다");
        return jwt.issue(req.userId(), role);
    }
}
