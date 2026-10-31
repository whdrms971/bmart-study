package com.baropick.delivery.testsupport;

import java.time.Duration;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.baropick.delivery.auth.AuthKeys;
import com.baropick.delivery.config.SecurityConfig;

/** Mock OMS/MFC 가 Delivery 의 내부 HTTP 계약을 실제로 호출하기 위한 클라이언트 (in-process 우회 금지). */
@Component
@Profile("local-test")
public class LocalServerClient {
    private final Environment env;
    private final AuthKeys keys;
    private final RestClient http;

    public LocalServerClient(Environment env, AuthKeys keys) {
        this.env = env;
        this.keys = keys;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3));
        f.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    public String baseUrl() {
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        return "http://127.0.0.1:" + port;
    }

    public RestClient.RequestBodySpec post(String path) {
        return http.post().uri(baseUrl() + path).header(SecurityConfig.INTERNAL_HEADER, keys.internalToken());
    }
}
