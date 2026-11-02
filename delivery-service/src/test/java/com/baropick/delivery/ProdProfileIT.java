package com.baropick.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.baropick.delivery.testsupport.simulation.SimulationService;

/** D-33: 운영 프로필에는 시뮬레이터·Mock·테스트 로그인·제어 API 가 존재하지 않는다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:prodcheck;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "delivery.dispatch.scheduler-enabled=false", "kakao.rest-api-key="
})
@ActiveProfiles("prod")
class ProdProfileIT {

    @LocalServerPort int port;
    @Autowired ApplicationContext ctx;

    @Test
    @DisplayName("D-33 prod: 테스트 지원 빈 없음, /test-api·테스트 로그인·Mock 내부 엔드포인트 접근 불가, Mock 테이블 미생성")
    void prodHasNoTestSupport() {
        assertThat(ctx.getBeanNamesForType(SimulationService.class)).isEmpty();
        assertThat(ctx.containsBean("mockOmsService")).isFalse();
        assertThat(ctx.containsBean("mockMfcService")).isFalse();
        assertThat(ctx.containsBean("testLoginController")).isFalse();
        RestClient http = RestClient.builder().baseUrl("http://127.0.0.1:" + port).defaultStatusHandler(s -> true, (q, r) -> {}).build();
        int login = http.post().uri("/api/auth/test-login").contentType(MediaType.APPLICATION_JSON)
                .body("{\"userId\":\"ADMIN-TEST\",\"passcode\":\"whatever-123\"}").retrieve().toBodilessEntity().getStatusCode().value();
        assertThat(login).isEqualTo(404);
        int sim = http.post().uri("/test-api/simulations").contentType(MediaType.APPLICATION_JSON).body("{}").retrieve()
                .toBodilessEntity().getStatusCode().value();
        assertThat(sim).isIn(401, 403, 404);
        int mockEvt = http.post().uri("/mock-oms/internal/events").contentType(MediaType.APPLICATION_JSON).body("{}").retrieve()
                .toBodilessEntity().getStatusCode().value();
        assertThat(mockEvt).isIn(401, 403, 404);
        var jdbc = ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        Integer mockTables = jdbc.queryForObject("select count(*) from information_schema.tables where lower(table_name) like 'mock_%'", Integer.class);
        assertThat(mockTables).isZero();
    }

    @Test
    @DisplayName("prod: 확정 정책값 P-01~P-10 · 고객 도착 · 보관 기간 적용 (2026-11-02 결정)")
    void prodDecidedPolicies() {
        var props = ctx.getBean(com.baropick.delivery.config.DeliveryProperties.class);
        var p = props.policy();
        assertThat(p.offerTtlSeconds()).isEqualTo(60);                       // P-01
        assertThat(p.dispatchRadiusMeters()).isEqualTo(3000);                // P-02
        assertThat(p.riderLocationFreshnessSeconds()).isEqualTo(300);        // P-02
        assertThat(p.maxDispatchAttempts()).isEqualTo(3);                    // P-03
        assertThat(p.dispatchRetryIntervalSeconds()).isEqualTo(30);          // P-03
        assertThat(p.dispatchHours()).isEqualTo("09:00-24:00");              // P-04 (local-test 의 24시간 값이 섞이지 않음)
        assertThat(p.dispatchTimeZone()).isEqualTo("Asia/Seoul");
        assertThat(p.exposeRiderLocationFrom()).isEqualTo(com.baropick.delivery.domain.DeliveryStatus.PICKED_UP); // P-05
        assertThat(p.locationFutureSkewSeconds()).isEqualTo(30);             // P-06
        assertThat(p.locationMaxDelaySeconds()).isEqualTo(120);              // P-06
        assertThat(p.locationMaxSpeedMps()).isEqualTo(50.0);                 // P-07
        assertThat(p.etaMinIntervalSeconds()).isEqualTo(60);                 // P-08
        assertThat(p.maxConcurrentDeliveries()).isEqualTo(3);                // P-10
        assertThat(p.placeSearchPerMinute()).isEqualTo(10);                  // SEC-03
        assertThat(props.retention().locationHistoryDays()).isEqualTo(30);   // P-09
        assertThat(props.retention().proofPhotoDays()).isEqualTo(90);        // P-09
        assertThat(props.retention().purgeEnabled()).isTrue();
        assertThat(props.arrival().maxDistanceMeters()).isEqualTo(150.0);
        assertThat(props.arrival().maxLocationAgeSeconds()).isEqualTo(30);
        assertThat(props.arrival().maxAccuracyMeters()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("D-33 prod + local-test 동시 활성화 → 기동 실패")
    void mixedProfilesRejected() {
        assertThatThrownBy(() -> {
            var app = new SpringApplication(DeliveryServiceApplication.class);
            app.setAdditionalProfiles("prod", "local-test");
            app.run("--server.port=0", "--spring.datasource.url=jdbc:h2:mem:mixed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                    "--spring.datasource.username=sa", "--spring.datasource.password=", "--delivery.dispatch.scheduler-enabled=false").close();
        }).hasStackTraceContaining("동시에 활성화할 수 없습니다");
    }
}
