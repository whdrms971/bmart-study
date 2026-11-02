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
    @DisplayName("prod: 확정 정책값 적용 — P-01 60초, P-02 3000m, P-10 3건, 고객 도착 150m/30초/50m. 미확정 값은 미설정(기능 차단)")
    void prodDecidedPolicies() {
        var props = ctx.getBean(com.baropick.delivery.config.DeliveryProperties.class);
        assertThat(props.policy().offerTtlSeconds()).isEqualTo(60);
        assertThat(props.policy().dispatchRadiusMeters()).isEqualTo(3000);
        assertThat(props.policy().maxConcurrentDeliveries()).isEqualTo(3);
        assertThat(props.arrival().maxDistanceMeters()).isEqualTo(150.0);
        assertThat(props.arrival().maxLocationAgeSeconds()).isEqualTo(30);
        assertThat(props.arrival().maxAccuracyMeters()).isEqualTo(50.0);
        // 미확정(P-03 재시도 횟수, P-05 위치 공개 시점 등)은 운영에서 기본값이 없어야 한다
        assertThat(props.policy().maxDispatchAttempts()).isNull();
        assertThat(props.policy().exposeRiderLocationFrom()).isNull();
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
