package com.baropick.delivery.handover;

import java.time.Duration;

import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.baropick.delivery.auth.AuthKeys;
import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.config.SecurityConfig;

/** MFC 인계 HTTP 어댑터. base-url 미설정 + local-test 이면 같은 서버의 Mock MFC 를 호출한다. */
@Component
public class HttpMfcHandoverAdapter implements MfcHandoverPort {

    private final DeliveryProperties props;
    private final Environment env;
    private final AuthKeys keys;
    private final RestClient http;

    public HttpMfcHandoverAdapter(DeliveryProperties props, Environment env, AuthKeys keys) {
        this.props = props;
        this.env = env;
        this.keys = keys;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    private String base() {
        String b = props.mfcClient().baseUrl();
        if (b != null && !b.isBlank()) return b;
        String port = env.getProperty("local.server.port");
        if (port != null && env.matchesProfiles("local-test")) return "http://127.0.0.1:" + port;
        throw ApiException.unavailable("MFC_NOT_CONFIGURED", "MFC 연동 주소가 설정되지 않았습니다");
    }

    @Override
    public HandoverResult authorizeAndCommit(HandoverCommand cmd) {
        try {
            return http.post().uri(base() + "/mock-mfc/internal/handovers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SecurityConfig.INTERNAL_HEADER, keys.internalToken())
                    .body(cmd).retrieve().body(HandoverResult.class);
        } catch (HttpClientErrorException e) {
            // 4xx 는 MFC 의 명시적 판정
            throw ApiException.conflict("MFC_REJECTED", "MFC 응답 오류: " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new MfcUnavailableException("MFC 인계 응답 불명: " + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public HandoverResult query(String executionId) {
        try {
            return http.get().uri(base() + "/mock-mfc/internal/handovers/{executionId}", executionId)
                    .header(SecurityConfig.INTERNAL_HEADER, keys.internalToken())
                    .retrieve().body(HandoverResult.class);
        } catch (RestClientException e) {
            throw new MfcUnavailableException("MFC 인계 결과 조회 실패: " + e.getClass().getSimpleName(), e);
        }
    }
}
