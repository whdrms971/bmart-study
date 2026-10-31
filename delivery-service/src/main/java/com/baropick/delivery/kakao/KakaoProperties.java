package com.baropick.delivery.kakao;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 카카오 REST 연동 설정. restApiKey 는 서버 환경변수로만 주입되며 toString 에서 가린다. */
@ConfigurationProperties(prefix = "kakao")
public record KakaoProperties(String restApiKey, String localBaseUrl, String mobilityBaseUrl, Integer connectTimeoutMs, Integer readTimeoutMs) {

    public boolean hasKey() { return restApiKey != null && !restApiKey.isBlank(); }

    @Override
    public String toString() {
        return "KakaoProperties[restApiKey=" + (hasKey() ? "***" : "<unset>") + ", localBaseUrl=" + localBaseUrl
                + ", mobilityBaseUrl=" + mobilityBaseUrl + "]";
    }
}
