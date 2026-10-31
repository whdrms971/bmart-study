package com.baropick.delivery.testsupport.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.baropick.delivery.testsupport.simulation.SimRoute.Pt;

/** D-26: 결정적 재생 — 같은 입력이면 같은 출력, 가상 시간·거리·seq 순서 일관. */
class RoutePlaybackTest {

    static SimRoute line() {
        // 위도 방향 약 1,112m 직선 (엔진 단위 테스트용 합성 경로, 지도 경로 아님)
        return new SimRoute(SimRoute.Source.FIXTURE, "UNIT", List.of(new Pt(35.0, 129.0), new Pt(35.005, 129.0), new Pt(35.01, 129.0)),
                null, null, "unit");
    }

    static List<String> run(int rate, List<FaultSpec> faults) {
        RoutePlayback p = new RoutePlayback(line(), 10, faults, 42);
        List<String> out = new ArrayList<>();
        while (!p.finished()) {
            for (var e : p.tick(1000L * rate).toSend()) out.add(e.seq() + ":" + String.format("%.6f", e.lat()) + ":" + e.virtualMs());
        }
        return out;
    }

    @Test
    void deterministic() {
        var faults = List.of(new FaultSpec(FaultSpec.Type.ANOMALY, 0.5, null, null), new FaultSpec(FaultSpec.Type.REORDER, 0.3, null, null));
        assertThat(run(5, faults)).isEqualTo(run(5, faults));
    }

    @Test
    void virtualTimeMatchesDistanceOverSpeed() {
        RoutePlayback p = new RoutePlayback(line(), 10, List.of(), 1);
        while (!p.finished()) p.tick(1000);
        assertThat(p.distance()).isEqualTo(line().totalMeters());
        assertThat(p.virtualMs() / 1000.0).isBetween(line().totalMeters() / 10 - 1, line().totalMeters() / 10 + 1);
    }

    @Test
    void ratesCoverSameDistanceWithFewerTicks() {
        assertThat(run(1, List.of()).size()).isGreaterThan(run(10, List.of()).size() * 8);
    }

    @Test
    void seqStrictlyIncreasingWithoutFaults() {
        long prev = 0;
        RoutePlayback p = new RoutePlayback(line(), 10, List.of(), 1);
        while (!p.finished()) {
            for (var e : p.tick(3000).toSend()) {
                assertThat(e.seq()).isGreaterThan(prev);
                prev = e.seq();
            }
        }
    }

    @Test
    void stopHoldsPosition() {
        RoutePlayback p = new RoutePlayback(line(), 10, List.of(new FaultSpec(FaultSpec.Type.STOP, 0.5, null, 20)), 1);
        double at = -1;
        int heldTicks = 0;
        while (!p.finished()) {
            var o = p.tick(1000);
            if (p.stopped()) {
                if (at < 0) at = p.distance();
                assertThat(p.distance()).isEqualTo(at);
                heldTicks++;
            }
        }
        assertThat(at).isCloseTo(line().totalMeters() / 2, org.assertj.core.data.Offset.offset(1.0));
        assertThat(heldTicks).isBetween(18, 21);
    }

    @Test
    void dropAndDelay() {
        RoutePlayback p = new RoutePlayback(line(), 10, List.of(new FaultSpec(FaultSpec.Type.DROP, 0.2, 2, null),
                new FaultSpec(FaultSpec.Type.DELAY, 0.5, 3, null)), 1);
        int ticks = 0, sent = 0;
        boolean sawBatch = false;
        while (!p.finished()) {
            var o = p.tick(1000);
            ticks++;
            sent += o.toSend().size();
            if (o.toSend().size() >= 3) sawBatch = true;
        }
        assertThat(sent).isEqualTo(ticks - 2);
        assertThat(sawBatch).isTrue();
    }

    @Test
    void finishedIsNotDeliveryCompletion() {
        RoutePlayback p = new RoutePlayback(line(), 10, List.of(), 1);
        RoutePlayback.TickOutput last = null;
        while (!p.finished()) last = p.tick(10_000);
        assertThat(last.events()).anyMatch(e -> e.contains("배송 완료 아님"));
        assertThat(p.tick(1000).toSend()).isEmpty();
    }
}
