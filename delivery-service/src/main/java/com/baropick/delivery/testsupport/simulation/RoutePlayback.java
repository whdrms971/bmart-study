package com.baropick.delivery.testsupport.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 결정적 경로 재생 엔진 (순수 계산, 스레드·시계 의존 없음).
 * 같은 (경로, 속도, 장애 목록, seed, 틱 크기 시퀀스) 입력이면 항상 같은 출력이 나온다 — 자동 테스트는 실제 대기 없이 검증한다.
 * 한 틱 = 가상 시간 virtualDeltaMs 진행 후 현재 위치 1개를 "생성"한다. 장애에 따라 전송 목록이 달라진다.
 */
public final class RoutePlayback {

    public record Emission(long seq, double lat, double lng, double accuracyMeters, long virtualMs, String tag) {}

    public record TickOutput(List<Emission> toSend, List<String> events, double progressedMeters, long virtualMs, boolean finished,
                             boolean connected) {}

    private final SimRoute route;
    private final double speedMps;
    private final List<FaultSpec> pending;
    private final Random random;
    private final long seed;

    private long virtualMs;
    private double distance;
    private long seq;
    private long stopRemainingMs;
    private int dropTicks, delayTicks, disconnectTicks;
    private final List<Emission> delayBuffer = new ArrayList<>();
    private Emission heldForReorder;
    private boolean finished;

    public RoutePlayback(SimRoute route, double speedMps, List<FaultSpec> faults, long seed) {
        if (!(speedMps > 0 && speedMps <= 40)) throw new IllegalArgumentException("speedMps 는 0 초과 40 이하");
        this.route = route;
        this.speedMps = speedMps;
        this.pending = new ArrayList<>(faults == null ? List.of() : faults);
        this.pending.sort((a, b) -> Double.compare(a.atFraction(), b.atFraction()));
        this.seed = seed;
        this.random = new Random(seed);
    }

    /** 진행 중 장애 추가(관리 화면 "지금 주입"). 현재 진행률에서 즉시 발동. */
    public void injectNow(FaultSpec.Type type, Integer durationTicks, Integer durationSeconds) {
        pending.add(0, new FaultSpec(type, Math.min(1.0, progressFraction()), durationTicks, durationSeconds));
    }

    public TickOutput tick(long virtualDeltaMs) {
        if (virtualDeltaMs <= 0) throw new IllegalArgumentException("virtualDeltaMs > 0");
        List<String> events = new ArrayList<>();
        List<Emission> send = new ArrayList<>();
        if (finished) return new TickOutput(send, List.of("ALREADY_FINISHED"), distance, virtualMs, true, true);

        advance(virtualDeltaMs, events);
        SimRoute.Pt p = route.at(distance);
        Emission current = new Emission(++seq, p.lat(), p.lng(), 5.0, virtualMs, "NORMAL");
        boolean reachedEnd = distance >= route.totalMeters();

        // 진행률 기반 장애 발동
        boolean duplicate = false, reorder = false, anomaly = false;
        while (!pending.isEmpty() && pending.get(0).atFraction() <= progressFraction() + 1e-9) {
            FaultSpec f = pending.remove(0);
            events.add("FAULT_TRIGGERED " + f.type());
            switch (f.type()) {
                case STOP -> stopRemainingMs += f.durationSeconds() * 1000L;
                case DROP -> dropTicks += f.durationTicks();
                case DELAY -> delayTicks += f.durationTicks();
                case DISCONNECT -> disconnectTicks += f.durationTicks();
                case DUPLICATE -> duplicate = true;
                case REORDER -> reorder = true;
                case ANOMALY -> anomaly = true;
            }
        }

        boolean connected = true;
        if (disconnectTicks > 0) {
            disconnectTicks--;
            connected = false;
            events.add("DISCONNECTED seq=" + current.seq());
            if (disconnectTicks == 0) events.add("RECONNECT_NEXT_TICK");
        } else if (dropTicks > 0) {
            dropTicks--;
            events.add("DROPPED seq=" + current.seq());
        } else if (delayTicks > 0) {
            delayTicks--;
            delayBuffer.add(current);
            events.add("DELAYED seq=" + current.seq());
            if (delayTicks == 0) {
                send.addAll(delayBuffer);
                events.add("DELAY_FLUSH count=" + delayBuffer.size());
                delayBuffer.clear();
            }
        } else if (reorder && !reachedEnd) {
            heldForReorder = current;
            events.add("HELD_FOR_REORDER seq=" + current.seq());
        } else {
            send.add(current);
            if (heldForReorder != null) {
                send.add(heldForReorder);
                events.add("REORDERED_SENT seq=" + heldForReorder.seq() + " after " + current.seq());
                heldForReorder = null;
            }
            if (duplicate) {
                send.add(current);
                events.add("DUPLICATE_SENT seq=" + current.seq());
            }
            if (anomaly) {
                send.add(new Emission(++seq, 91.0, current.lng(), 5.0, virtualMs, "ANOMALY_OUT_OF_RANGE"));
                double jump = 0.2 + random.nextDouble() * 0.1; // 약 22~33km 순간이동
                send.add(new Emission(++seq, current.lat() + jump, current.lng(), 5.0, virtualMs + 1, "ANOMALY_TELEPORT"));
                events.add("ANOMALY_SENT");
            }
        }
        if (reachedEnd) {
            // 경로 끝: 보류 중인 좌표가 있으면 마지막 위치 뒤에 내보낸다
            if (!delayBuffer.isEmpty()) { send.addAll(delayBuffer); delayBuffer.clear(); }
            if (heldForReorder != null) { send.add(heldForReorder); heldForReorder = null; }
            if (send.stream().noneMatch(e -> e.seq() == current.seq()) && connected) send.add(current);
            finished = true;
            events.add("ROUTE_END_REACHED (배송 완료 아님)");
        }
        return new TickOutput(send, events, distance, virtualMs, finished, connected);
    }

    private void advance(long deltaMs, List<String> events) {
        long remaining = deltaMs;
        while (remaining > 0) {
            if (stopRemainingMs > 0) {
                long s = Math.min(stopRemainingMs, remaining);
                stopRemainingMs -= s;
                remaining -= s;
                virtualMs += s;
                if (stopRemainingMs == 0) events.add("STOP_ENDED");
                continue;
            }
            // 다음 STOP 발동 지점까지만 이동 (정차 위치 정확도 보장)
            double nextStopAt = Double.MAX_VALUE;
            for (FaultSpec f : pending) {
                if (f.type() == FaultSpec.Type.STOP) { nextStopAt = f.atFraction() * route.totalMeters(); break; }
            }
            double canMove = speedMps * remaining / 1000.0;
            double target = Math.min(route.totalMeters(), distance + canMove);
            if (nextStopAt > distance && target >= nextStopAt) target = nextStopAt;
            double moved = target - distance;
            long used = moved <= 0 ? remaining : Math.min(remaining, (long) Math.ceil(moved / speedMps * 1000.0));
            distance = target;
            virtualMs += used;
            remaining -= used;
            if (distance >= route.totalMeters()) { virtualMs += remaining; remaining = 0; }
            if (target == nextStopAt) {
                FaultSpec f = pending.stream().filter(x -> x.type() == FaultSpec.Type.STOP).findFirst().orElseThrow();
                pending.remove(f);
                stopRemainingMs += f.durationSeconds() * 1000L;
                events.add("STOP_STARTED " + f.durationSeconds() + "s");
            }
        }
    }

    public double progressFraction() { return route.totalMeters() == 0 ? 1 : distance / route.totalMeters(); }
    public double distance() { return distance; }
    public long virtualMs() { return virtualMs; }
    public boolean finished() { return finished; }
    public long seed() { return seed; }
    public double speedMps() { return speedMps; }
    public boolean stopped() { return stopRemainingMs > 0; }
}
