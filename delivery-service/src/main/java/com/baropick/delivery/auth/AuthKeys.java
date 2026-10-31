package com.baropick.delivery.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.baropick.delivery.common.Ids;
import com.baropick.delivery.config.DeliveryProperties;

/**
 * 서명 비밀·내부 호출 토큰. 설정이 없으면 기동마다 임의 생성한다(값은 로그에 출력하지 않음).
 */
@Component
public class AuthKeys {
    private static final Logger log = LoggerFactory.getLogger(AuthKeys.class);

    private final SecretKey jwtKey;
    private final String internalToken;

    public AuthKeys(DeliveryProperties props) {
        String secret = props.auth().secret();
        if (secret == null || secret.isBlank()) {
            secret = Ids.randomToken(32);
            log.warn("DELIVERY_AUTH_SECRET 미설정: 임시 서명 비밀을 생성했습니다. 서버 재기동 시 기존 토큰은 무효가 됩니다.");
        } else if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("DELIVERY_AUTH_SECRET 은 32바이트 이상이어야 합니다");
        }
        this.jwtKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        String it = props.auth().internalToken();
        if (it == null || it.isBlank()) {
            it = Ids.randomToken(24);
            log.warn("DELIVERY_INTERNAL_TOKEN 미설정: 임시 내부 토큰을 생성했습니다 (동일 JVM의 Mock만 호출 가능).");
        }
        this.internalToken = it;
    }

    public SecretKey jwtKey() { return jwtKey; }

    public String internalToken() { return internalToken; }

    public boolean matchesInternal(String candidate) {
        if (candidate == null) return false;
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), internalToken.getBytes(StandardCharsets.UTF_8));
    }
}
