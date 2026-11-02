package com.baropick.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.baropick.delivery.support.IntegrationTestBase;

/** P-04 배차 가능 시간: 운영값(09:00~24:00, Asia/Seoul)을 적용한 컨텍스트에서 배차 동작 확인. */
class DispatchHoursIT extends IntegrationTestBase {

    @DynamicPropertySource
    static void hours(DynamicPropertyRegistry r) {
        r.add("delivery.policy.dispatch-hours", () -> "09:00-24:00");
        r.add("delivery.policy.dispatch-time-zone", () -> "Asia/Seoul");
    }

    @Test
    @DisplayName("P-04 03:30 KST 배송 요청 → 배차하지 않고 대기(시도 횟수 미소진) → 09:00 KST 에 배차 제안")
    void outsideHoursWaitsUntilOpen() {
        mclock().set(ZonedDateTime.of(2026, 11, 3, 3, 30, 0, 0, ZoneId.of("Asia/Seoul")).toInstant());
        String d = remoteDelivery("C-TEST-01", 1);
        onDuty("R-TEST-01");
        var night = dispatch.tryDispatch(d);
        assertThat(night.result()).isEqualTo("OUTSIDE_DISPATCH_HOURS");
        assertThat(night.note()).contains("09:00~24:00 (Asia/Seoul)");
        assertThat(status(d)).isEqualTo("REQUESTED");
        assertThat(offerFor("R-TEST-01", d)).isNull();
        // 여러 번 시도해도 시도 횟수를 쓰지 않는다(소진·관리자 대기로 가지 않음)
        for (int i = 0; i < 5; i++) assertThat(dispatch.tryDispatch(d).result()).isEqualTo("OUTSIDE_DISPATCH_HOURS");
        assertThat(jdbcTemplate.queryForObject("select count(*) from dispatch_attempt where delivery_id=?", Integer.class, d)).isZero();

        mclock().set(ZonedDateTime.of(2026, 11, 3, 9, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant());
        assertThat(dispatch.tryDispatch(d).result()).isEqualTo("OFFERED");
        assertThat(offerFor("R-TEST-01", d)).isNotNull();
    }
}
