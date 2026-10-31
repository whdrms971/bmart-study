package com.baropick.delivery.config;

import java.io.IOException;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import com.baropick.delivery.auth.AuthKeys;
import com.baropick.delivery.auth.JwtService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class SecurityConfig {

    public static final String INTERNAL_HEADER = "X-Internal-Token";

    @Bean
    JwtEncoder jwtEncoder(AuthKeys keys) {
        return NimbusJwtEncoder.withSecretKey(keys.jwtKey()).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(AuthKeys keys) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(keys.jwtKey()).macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtService.ISSUER));
        return d;
    }

    /** OMS/MFC → Delivery 내부 호출 및 Mock 수신 엔드포인트: 내부 토큰 필수. */
    @Bean
    @Order(1)
    SecurityFilterChain internalChain(HttpSecurity http, AuthKeys keys) throws Exception {
        http.securityMatcher("/internal/**", "/mock-oms/internal/**", "/mock-mfc/internal/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalTokenFilter(keys), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("INTERNAL"));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/ws", "/ws/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/test-login").permitAll()
                        .requestMatchers("/api/customer/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/rider/**").hasRole("RIDER")
                        // 테스트 지원 API: 컨트롤러가 local-test 프로필에만 존재. 역할 세부 검사는 컨트롤러에서 수행
                        .requestMatchers("/test-api/**").hasAnyRole("ADMIN", "CUSTOMER", "RIDER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(conv)));
        return http.build();
    }

    static final class InternalTokenFilter extends OncePerRequestFilter {
        private final AuthKeys keys;

        InternalTokenFilter(AuthKeys keys) { this.keys = keys; }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            if (keys.matchesInternal(req.getHeader(INTERNAL_HEADER))) {
                var auth = new UsernamePasswordAuthenticationToken("internal", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(req, res);
        }
    }
}
