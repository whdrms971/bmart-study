package com.baropick.delivery.config;

import java.security.Principal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.baropick.delivery.auth.JwtService;
import com.baropick.delivery.tracking.TrackingQueryService;

/**
 * STOMP over WebSocket (/ws). CONNECT 시 Authorization: Bearer 토큰 검증,
 * SUBSCRIBE 시 토픽 권한 검사(고객=본인 주문, 라이더=본인 토픽, 관리자=admin) — D-06.
 * 클라이언트 → 서버 SEND 는 허용하지 않는다(위치 전송은 HTTP 로만).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private static final Pattern ORDER_TOPIC = Pattern.compile("^/topic/orders/([A-Za-z0-9_-]{1,40})$");
    private static final Pattern RIDER_TOPIC = Pattern.compile("^/topic/riders/([A-Za-z0-9_-]{1,40})$");

    private final JwtService jwt;
    private final TrackingQueryService tracking;

    public WebSocketConfig(JwtService jwt, @Lazy TrackingQueryService tracking) {
        this.jwt = jwt;
        this.tracking = tracking;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 모바일 앱/로컬 관리자 UI 용. 인증은 STOMP CONNECT 토큰으로 수행
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic").setHeartbeatValue(new long[] {10000, 10000})
                .setTaskScheduler(heartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
    }

    private org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler heartbeatScheduler() {
        var s = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("ws-heartbeat-");
        s.initialize();
        return s;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (acc == null || acc.getCommand() == null) return message;
                StompCommand cmd = acc.getCommand();
                if (cmd == StompCommand.CONNECT) {
                    String h = acc.getFirstNativeHeader("Authorization");
                    if (h == null || !h.startsWith("Bearer ")) throw new IllegalArgumentException("UNAUTHENTICATED");
                    try {
                        Jwt token = jwt.decode(h.substring(7));
                        acc.setUser(new JwtAuthenticationToken(token,
                                List.of(new SimpleGrantedAuthority("ROLE_" + token.getClaimAsString("role"))), token.getSubject()));
                    } catch (JwtException e) {
                        throw new IllegalArgumentException("INVALID_TOKEN");
                    }
                } else if (cmd == StompCommand.SUBSCRIBE) {
                    authorizeSubscribe(acc.getUser(), acc.getDestination());
                } else if (cmd == StompCommand.SEND) {
                    throw new IllegalArgumentException("SEND_NOT_ALLOWED");
                }
                return message;
            }
        });
    }

    void authorizeSubscribe(Principal user, String dest) {
        if (!(user instanceof JwtAuthenticationToken auth) || dest == null) throw new IllegalArgumentException("UNAUTHENTICATED");
        String role = auth.getToken().getClaimAsString("role");
        String uid = auth.getToken().getSubject();
        if ("ADMIN".equals(role)) return;
        Matcher m = ORDER_TOPIC.matcher(dest);
        if (m.matches() && "CUSTOMER".equals(role) && tracking.customerOwnsOrder(m.group(1), uid)) return;
        m = RIDER_TOPIC.matcher(dest);
        if (m.matches() && "RIDER".equals(role) && m.group(1).equals(uid)) return;
        throw new IllegalArgumentException("FORBIDDEN_DESTINATION");
    }
}
