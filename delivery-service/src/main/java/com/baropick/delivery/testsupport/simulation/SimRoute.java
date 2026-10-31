package com.baropick.delivery.testsupport.simulation;

import java.time.Instant;
import java.util.List;

import com.baropick.delivery.common.Geo;

/** 재생용 경로. 좌표는 (위도, 경도). cumulative[i] = 시작점부터 i 번째 점까지 누적 거리(m). */
public final class SimRoute {

    public enum Source { FIXTURE, KAKAO_DIRECTIONS }

    public record Pt(double lat, double lng) {}

    private final Source source;
    private final String routeRef;
    private final List<Pt> points;
    private final double[] cumulative;
    private final Integer providerDurationSeconds;
    private final Instant fetchedAt;
    private final String note;

    public SimRoute(Source source, String routeRef, List<Pt> points, Integer providerDurationSeconds, Instant fetchedAt, String note) {
        if (points.size() < 2) throw new IllegalArgumentException("경로 점이 2개 이상 필요합니다");
        this.source = source;
        this.routeRef = routeRef;
        this.points = List.copyOf(points);
        this.providerDurationSeconds = providerDurationSeconds;
        this.fetchedAt = fetchedAt;
        this.note = note;
        this.cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            Pt a = points.get(i - 1), b = points.get(i);
            cumulative[i] = cumulative[i - 1] + Geo.distanceMeters(a.lat(), a.lng(), b.lat(), b.lng());
        }
    }

    public double totalMeters() { return cumulative[cumulative.length - 1]; }

    /** 누적 거리 d 지점의 좌표(구간 선형 보간). */
    public Pt at(double d) {
        if (d <= 0) return points.get(0);
        if (d >= totalMeters()) return points.get(points.size() - 1);
        int lo = 0, hi = cumulative.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] <= d) lo = mid; else hi = mid;
        }
        double seg = cumulative[hi] - cumulative[lo];
        double t = seg == 0 ? 0 : (d - cumulative[lo]) / seg;
        Pt a = points.get(lo), b = points.get(hi);
        return new Pt(a.lat() + (b.lat() - a.lat()) * t, a.lng() + (b.lng() - a.lng()) * t);
    }

    public Source source() { return source; }
    public String routeRef() { return routeRef; }
    public List<Pt> points() { return points; }
    public Integer providerDurationSeconds() { return providerDurationSeconds; }
    public Instant fetchedAt() { return fetchedAt; }
    public String note() { return note; }
}
