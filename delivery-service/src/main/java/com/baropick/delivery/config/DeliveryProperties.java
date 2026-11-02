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
        TestAccounts testAccounts,
        Arrival arrival,
        Retention retention) {

    public DeliveryProperties {
        if (arrival == null) arrival = new Arrival(null, null, null);
        if (auth == null) auth = new Auth(null, 720, null);
        if (outbox == null) outbox = new Outbox(null, 2000L);
        if (mfcClient == null) mfcClient = new MfcClient(null);
        if (policy == null) policy = new Policy(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        if (retention == null) retention = new Retention(null, null, false, null);
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
            String proofStorageDir,
            /** P-02 근거리 배차 반경 기본값(m). radius-policy=ENFORCE 인 MFC 에 radius-meters 가 없을 때 사용 (2026-11-02 결정 3000) */
            Integer dispatchRadiusMeters,
            /** P-03 제안 전원 거절·만료로 시도가 끝난 뒤 다음 시도까지 대기(초) (2026-11-02 결정 30) */
            Integer dispatchRetryIntervalSeconds,
            /** P-04 배차 가능 시간 "HH:mm-HH:mm" (종료 24:00 허용) (2026-11-02 결정 09:00-24:00) */
            String dispatchHours,
            /** P-04 배차 가능 시간 판단 시간대 (예: Asia/Seoul) */
            String dispatchTimeZone,
            /** SEC-03 고객 주소 검색 사용자별 분당 최대 호출 수 (2026-11-02 결정 10) */
            Integer placeSearchPerMinute,
            /** 라이더 길안내 경로 같은 배송·목적지 재조회 최소 간격(초) [설계 제안 15] — 유료 길찾기 API 보호 */
            Integer navigationMinRefreshSeconds) {

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

    /**
     * 고객 도착 판정 정책 (2026-11-02 결정, DECISIONS.md A-01~A-03).
     * maxDistanceMeters: 배송지 좌표와 라이더 최신 위치의 직선거리 상한(이하 허용)
     * maxLocationAgeSeconds: 위치 측정 후 경과 상한 / maxAccuracyMeters: 위치 정확도 반경 상한(이하 허용)
     * 미설정이면 도착 처리가 차단된다(POLICY_NOT_CONFIGURED).
     */
    public record Arrival(Double maxDistanceMeters, Integer maxLocationAgeSeconds, Double maxAccuracyMeters) {}

    /**
     * P-09 보관 기간 (2026-11-02 결정: 위치 이력 30일, 증빙 사진 90일). 지난 데이터는 매일 자동 삭제.
     * 상태 이력·최신 위치·증빙 기록(해시·시각)은 삭제하지 않는다. 기간 미설정이면 해당 항목은 삭제하지 않는다(무기한).
     */
    public record Retention(Integer locationHistoryDays, Integer proofPhotoDays, boolean purgeEnabled, String purgeCron) {}

    public record TestAccounts(String passcode, List<String> customers, List<String> riders, List<String> admins) {
        public TestAccounts {
            if (customers == null) customers = List.of();
            if (riders == null) riders = List.of();
            if (admins == null) admins = List.of();
        }
    }
}
