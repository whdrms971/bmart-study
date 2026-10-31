package com.baropick.delivery.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.LocationMode;

/**
 * Delivery 설정. 정책값(policy)은 의도적으로 기본값을 두지 않는다 — 미설정은 0/무제한이 아니라 "기능 차단"이다.
 */
@ConfigurationProperties(prefix = "delivery")
public record DeliveryProperties(
        Auth auth,
        Outbox outbox,
        MfcClient mfcClient,
        Policy policy,
        List<MfcSiteProps> mfcs,
        Simulation simulation,
        TestAccounts testAccounts) {

    public DeliveryProperties {
        if (auth == null) auth = new Auth(null, 720, null);
        if (outbox == null) outbox = new Outbox(null, 2000L);
        if (mfcClient == null) mfcClient = new MfcClient(null);
        if (policy == null) policy = new Policy(null, null, null, null, null, null, null, null, null, null);
        if (mfcs == null) mfcs = List.of();
        if (simulation == null) simulation = new Simulation(false, 1000L);
        if (testAccounts == null) testAccounts = new TestAccounts(null, List.of(), List.of(), List.of());
    }

    public record Auth(String secret, Integer tokenTtlMinutes, String internalToken) {}

    public record Outbox(String omsEndpoint, Long relayIntervalMs) {}

    public record MfcClient(String baseUrl) {}

    public record Policy(
            Integer offerTtlSeconds,
            Integer maxDispatchAttempts,
            Integer riderLocationFreshnessSeconds,
            Integer maxConcurrentDeliveries,
            DeliveryStatus exposeRiderLocationFrom,
            Integer locationFutureSkewSeconds,
            Integer locationMaxDelaySeconds,
            Double locationMaxSpeedMps,
            Integer etaMinIntervalSeconds,
            String proofStorageDir) {

        public static <T> T require(T v, String name) {
            if (v == null) {
                throw ApiException.unavailable("POLICY_NOT_CONFIGURED",
                        "운영 정책값 '" + name + "'이(가) 설정되지 않아 이 기능을 실행할 수 없습니다 (DECISIONS.md 참조)");
            }
            return v;
        }
    }

    public record MfcSiteProps(String id, String name, boolean virtual, Double latitude, Double longitude,
                               LocationMode locationMode, RadiusPolicy radiusPolicy, Integer radiusMeters) {}

    public enum RadiusPolicy { ENFORCE, TEST_BYPASS }

    public record Simulation(boolean enabled, Long tickRealMs) {}

    public record TestAccounts(String passcode, List<String> customers, List<String> riders, List<String> admins) {
        public TestAccounts {
            if (customers == null) customers = List.of();
            if (riders == null) riders = List.of();
            if (admins == null) admins = List.of();
        }
    }
}
