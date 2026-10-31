package com.baropick.delivery.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.baropick.delivery.config.DeliveryProperties;

@Service
public class JwtService {
    public static final String ISSUER = "baropick-delivery";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder, JwtDecoder decoder, Clock clock, DeliveryProperties props) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.clock = clock;
        this.ttl = Duration.ofMinutes(props.auth().tokenTtlMinutes() == null ? 720 : props.auth().tokenTtlMinutes());
    }

    public IssuedToken issue(String userId, Role role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER).subject(userId).issuedAt(now).expiresAt(now.plus(ttl))
                .claim("role", role.name())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedToken(token, userId, role, now.plus(ttl));
    }

    public Jwt decode(String token) { return decoder.decode(token); }

    public record IssuedToken(String accessToken, String userId, Role role, Instant expiresAt) {}
}
