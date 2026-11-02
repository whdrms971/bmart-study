package com.baropick.delivery.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DispatchHoursTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static Instant kst(int h, int m) { return ZonedDateTime.of(2026, 11, 2, h, m, 0, 0, SEOUL).toInstant(); }

    @Test
    @DisplayName("P-04 09:00-24:00 (Asia/Seoul): 08:59 닫힘, 09:00 열림, 23:59 열림, 00:00 닫힘")
    void nineToMidnight() {
        DispatchHours h = DispatchHours.parse("09:00-24:00", "Asia/Seoul");
        assertThat(h.isOpen(kst(8, 59))).isFalse();
        assertThat(h.isOpen(kst(9, 0))).isTrue();
        assertThat(h.isOpen(kst(23, 59))).isTrue();
        assertThat(h.isOpen(kst(0, 0))).isFalse();
        assertThat(h.isOpen(kst(3, 30))).isFalse();
        assertThat(h.nextOpen(kst(3, 30))).isEqualTo(kst(9, 0));
        assertThat(h.nextOpen(kst(12, 0))).isEqualTo(kst(12, 0));
        assertThat(h.label()).isEqualTo("09:00~24:00 (Asia/Seoul)");
    }

    @Test
    @DisplayName("UTC 서버 시계여도 한국 시간 기준으로 판단 (00:30 UTC = 09:30 KST 열림)")
    void zoneIndependent() {
        DispatchHours h = DispatchHours.parse("09:00-24:00", "Asia/Seoul");
        assertThat(h.isOpen(Instant.parse("2026-11-02T00:30:00Z"))).isTrue();
        assertThat(h.isOpen(Instant.parse("2026-11-02T15:30:00Z"))).isFalse(); // 00:30 KST
    }

    @Test
    @DisplayName("24시간 / 자정을 넘는 구간 / 잘못된 형식")
    void variants() {
        assertThat(DispatchHours.parse("00:00-24:00", "Asia/Seoul").isOpen(kst(3, 0))).isTrue();
        DispatchHours night = DispatchHours.parse("22:00-06:00", "Asia/Seoul");
        assertThat(night.isOpen(kst(23, 0))).isTrue();
        assertThat(night.isOpen(kst(5, 59))).isTrue();
        assertThat(night.isOpen(kst(6, 0))).isFalse();
        assertThat(night.nextOpen(kst(12, 0))).isEqualTo(kst(22, 0));
        assertThatThrownBy(() -> DispatchHours.parse("9시-24시", "Asia/Seoul")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> DispatchHours.parse(null, "Asia/Seoul")).isInstanceOf(IllegalArgumentException.class);
    }
}
