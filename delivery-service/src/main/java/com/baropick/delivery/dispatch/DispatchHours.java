package com.baropick.delivery.dispatch;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * P-04 배차 가능 시간 (2026-11-02 결정: 09:00~24:00, Asia/Seoul).
 * 형식 "HH:mm-HH:mm". 종료 "24:00" 은 자정까지. 시작 &gt; 종료이면 자정을 넘는 구간(예: 22:00-06:00).
 * 시작 = 종료(예: 00:00-24:00)는 24시간.
 */
public record DispatchHours(LocalTime start, LocalTime end, boolean endOfDay, ZoneId zone) {

    public static DispatchHours parse(String spec, String zone) {
        if (spec == null || spec.isBlank() || zone == null || zone.isBlank()) {
            throw new IllegalArgumentException("배차 가능 시간 설정 필요(delivery.policy.dispatch-hours / dispatch-time-zone)");
        }
        String[] p = spec.strip().split("-");
        if (p.length != 2) throw new IllegalArgumentException("dispatch-hours 형식은 HH:mm-HH:mm: " + spec);
        LocalTime s = LocalTime.parse(p[0].strip());
        boolean eod = p[1].strip().equals("24:00");
        LocalTime e = eod ? LocalTime.MIDNIGHT : LocalTime.parse(p[1].strip());
        return new DispatchHours(s, e, eod, ZoneId.of(zone.strip()));
    }

    public boolean allDay() { return endOfDay && start.equals(LocalTime.MIDNIGHT); }

    public boolean isOpen(Instant now) {
        if (allDay()) return true;
        LocalTime t = now.atZone(zone).toLocalTime();
        if (endOfDay) return !t.isBefore(start);
        if (start.isBefore(end)) return !t.isBefore(start) && t.isBefore(end);
        return !t.isBefore(start) || t.isBefore(end); // 자정을 넘는 구간
    }

    /** 다음 운영 시작 시각 (현재 운영 중이면 now). */
    public Instant nextOpen(Instant now) {
        if (isOpen(now)) return now;
        ZonedDateTime z = now.atZone(zone);
        ZonedDateTime candidate = z.with(start).withSecond(0).withNano(0);
        if (!candidate.isAfter(z)) candidate = candidate.plusDays(1);
        return candidate.toInstant();
    }

    public String label() {
        return String.format("%s~%s (%s)", start, endOfDay ? "24:00" : end.toString(), zone);
    }
}
