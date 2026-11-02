package com.baropick.delivery.testsupport.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Ids;
import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.domain.Delivery;
import com.baropick.delivery.domain.DeliveryRepository;
import com.baropick.delivery.domain.DeliveryStatus;
import com.baropick.delivery.domain.LocationMode;
import com.baropick.delivery.domain.LocationVerdict;
import com.baropick.delivery.tracking.DeliveryChanged;
import com.baropick.delivery.tracking.LocationIngestService.LocationPoint;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * SIMULATED_ROUTE 자동 배송 재생기.
 * 상태: CREATED → READY → RUNNING ↔ PAUSED → FINISHED / STOPPED / FAILED  (reset → READY, delete → 제거)
 * - 재생 종료(FINISHED)는 배송 완료가 아니다. 완료는 라이더 앱의 증빙 완료 API 로만 가능.
 * - 인계(PICKED_UP) 전 시작 거부, 재배정·취소·완료 시 즉시 주입 중단.
 * - 한 배송에 활성 재생기는 하나만.
 */
@Service
@Profile("local-test")
@ConditionalOnProperty(name = "delivery.simulation.enabled", havingValue = "true")
public class SimulationService {
    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    public enum State { CREATED, READY, RUNNING, PAUSED, FINISHED, STOPPED, FAILED }

    static final Set<State> ACTIVE = EnumSet.of(State.CREATED, State.READY, State.RUNNING, State.PAUSED);
    static final Set<Integer> RATES = Set.of(1, 5, 10);
    static final Set<DeliveryStatus> STARTABLE = EnumSet.of(DeliveryStatus.PICKED_UP, DeliveryStatus.IN_TRANSIT);

    public record CreateRequest(String deliveryId, String riderId, SimRoute.Source routeSource, String fixtureId, Integer rate,
                                Double speedMps, Long seed, List<FaultSpec> faults) {}

    /** dwellSent: 경로 끝 도달(FINISHED) 이후 배송지 정차 위치 전송 수. sent 에는 포함하지 않는다. */
    public record Counters(long ticks, long generated, long sent, long acceptedLatest, long history, long duplicate, long rejected, long stale,
                           long dwellSent) {}

    /** 경로 끝 도달 후 정차 위치를 계속 보내는 최대 시간(실시간). 무기한 전송 방지. */
    static final java.time.Duration DWELL_MAX = java.time.Duration.ofMinutes(30);

    public record LogEntry(Instant realAt, long virtualMs, String type, String detail) {}

    public record RoutePreview(String source, String routeRef, int pointCount, double totalMeters, Integer providerDurationSeconds,
                               Instant fetchedAt, String note, List<double[]> points) {}

    public record Status(String scenarioId, String deliveryId, String riderId, State state, String routeSource, String fixtureId,
                         int rate, double speedMps, long seed, double progressedMeters, double totalMeters, double progressFraction,
                         long virtualElapsedMs, Instant createdAt, Instant startedAt, Instant lastSentAt, double[] lastPosition,
                         boolean connected, boolean stopped, String lastError, String deliveryStatus, Counters counters,
                         List<FaultSpec> plannedFaults, List<LogEntry> recentLog, String label, boolean dwelling) {}

    private final class Run {
        final String scenarioId;
        final String deliveryId;
        final String riderId;
        final SimRoute.Source source;
        final String fixtureId;
        final double requestedSpeed;
        final long seed;
        final List<FaultSpec> faults;
        final Instant createdAt;
        volatile State state = State.CREATED;
        volatile int rate;
        SimRoute route;
        RoutePlayback playback;
        Instant startedAt;
        Instant virtualEpoch;
        Instant lastSentAt;
        double[] lastPosition;
        boolean connected = true;
        Integer assignmentVersion;
        String lastError;
        ScheduledFuture<?> future;
        long ticks, generated, sent, acceptedLatest, history, duplicate, rejected, stale;
        boolean dwelling;
        Instant dwellUntil;
        long dwellSent;
        final Deque<LogEntry> recent = new ArrayDeque<>();

        Run(String scenarioId, CreateRequest r, int rate, Instant now) {
            this.scenarioId = scenarioId;
            this.deliveryId = r.deliveryId();
            this.riderId = r.riderId();
            this.source = r.routeSource();
            this.fixtureId = r.fixtureId();
            this.requestedSpeed = r.speedMps() == null ? 0 : r.speedMps();
            this.seed = r.seed() == null ? 42L : r.seed();
            this.faults = r.faults() == null ? List.of() : List.copyOf(r.faults());
            this.rate = rate;
            this.createdAt = now;
        }
    }

    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final Map<String, String> activeByDelivery = new ConcurrentHashMap<>();
    private final DeliveryRepository deliveries;
    private final RouteProviders routes;
    private final SimulatedLocationInjector injector;
    private final Clock clock;
    private final JsonMapper json;
    private final long tickRealMs;
    private final boolean autoTick;
    private final Path logDir;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "sim-ticker");
        t.setDaemon(true);
        return t;
    });

    public SimulationService(DeliveryRepository deliveries, RouteProviders routes, SimulatedLocationInjector injector, Clock clock, JsonMapper json,
                             DeliveryProperties props, Environment env,
                             @Value("${delivery.simulation.auto-tick:true}") boolean autoTick,
                             @Value("${delivery.simulation.log-dir:../reports/runs/simulations}") String logDir) {
        if (env.matchesProfiles("prod")) throw new IllegalStateException("시뮬레이터는 운영(prod) 프로필에서 생성될 수 없습니다");
        this.deliveries = deliveries;
        this.routes = routes;
        this.injector = injector;
        this.clock = clock;
        this.json = json;
        this.tickRealMs = props.simulation().tickRealMs() == null ? 1000 : props.simulation().tickRealMs();
        this.autoTick = autoTick;
        this.logDir = Path.of(logDir);
    }

    // ------------------------------------------------------------------ 명령

    public synchronized Status create(CreateRequest req, String actor) {
        if (req.deliveryId() == null || req.riderId() == null || req.routeSource() == null) {
            throw ApiException.badRequest("INVALID_SCENARIO", "deliveryId, riderId, routeSource 필요");
        }
        if (req.routeSource() == SimRoute.Source.FIXTURE && (req.fixtureId() == null || req.fixtureId().isBlank())) {
            throw ApiException.badRequest("FIXTURE_REQUIRED", "FIXTURE 경로는 fixtureId 필요");
        }
        int rate = req.rate() == null ? 1 : req.rate();
        if (!RATES.contains(rate)) throw ApiException.badRequest("INVALID_RATE", "배속은 1, 5, 10 만 허용");
        Delivery d = deliveries.findById(req.deliveryId()).orElseThrow(() -> ApiException.notFound("DELIVERY_NOT_FOUND", req.deliveryId()));
        if (d.getLocationMode() != LocationMode.SIMULATED_ROUTE) {
            throw ApiException.conflict("NOT_SIMULATED_DELIVERY", "이 배송은 서버가 " + d.getLocationMode() + " 모드로 고정했습니다. 시뮬레이션 주입 불가");
        }
        String existing = activeByDelivery.get(d.getId());
        if (existing != null) throw ApiException.conflict("DUPLICATE_RUN", "이 배송에 이미 활성 시나리오가 있습니다: " + existing);
        String id = Ids.next("SIM");
        Run run = new Run(id, req, rate, clock.instant());
        runs.put(id, run);
        activeByDelivery.put(d.getId(), id);
        log(run, "CREATED", "by " + actor + " source=" + req.routeSource() + " fixture=" + req.fixtureId() + " rate=" + rate + " seed=" + run.seed
                + " faults=" + run.faults.size());
        return status(run);
    }

    /** 검증 + 경로 로드(미리보기). CREATED/READY → READY. 실패 시 FAILED. */
    public Status prepare(String id) {
        Run run = get(id);
        synchronized (run) {
            requireState(run, State.CREATED, State.READY);
            Delivery d = deliveries.findById(run.deliveryId).orElseThrow();
            try {
                SimRoute route = run.source == SimRoute.Source.FIXTURE
                        ? routes.fixture(run.fixtureId, d.getOriginLat(), d.getOriginLng(), d.getDestLat(), d.getDestLng())
                        : routes.kakao(d.getOriginLat(), d.getOriginLng(), d.getDestLat(), d.getDestLng());
                run.route = route;
                run.playback = newPlayback(run);
                run.state = State.READY;
                run.lastError = null;
                log(run, "READY", String.format("route=%s ref=%s points=%d total=%.0fm speed=%.1fm/s", route.source(), route.routeRef(),
                        route.points().size(), route.totalMeters(), run.playback.speedMps()));
            } catch (ApiException e) {
                fail(run, e.code() + ": " + e.getMessage());
                throw e;
            }
            return status(run);
        }
    }

    private RoutePlayback newPlayback(Run run) {
        double speed = run.requestedSpeed > 0 ? run.requestedSpeed : defaultSpeed(run);
        return new RoutePlayback(run.route, speed, run.faults, run.seed);
    }

    private double defaultSpeed(Run run) {
        if (run.route.source() == SimRoute.Source.KAKAO_DIRECTIONS && run.route.providerDurationSeconds() != null
                && run.route.providerDurationSeconds() > 0) {
            return Math.min(20, Math.max(2, run.route.totalMeters() / run.route.providerDurationSeconds()));
        }
        return routes.fixtures().stream().filter(f -> f.id().equals(run.fixtureId)).findFirst().map(RouteProviders.Fixture::defaultSpeedMps).orElse(6.0);
    }

    public Status start(String id) {
        Run run = get(id);
        synchronized (run) {
            if (run.state == State.RUNNING) throw ApiException.conflict("ALREADY_RUNNING", "이미 실행 중입니다");
            requireState(run, State.READY);
            Delivery d = authorizeStart(run);
            run.assignmentVersion = d.getAssignmentVersion();
            run.startedAt = clock.instant();
            run.virtualEpoch = run.startedAt;
            run.state = State.RUNNING;
            log(run, "STARTED", "deliveryStatus=" + d.getStatus() + " assignmentVersion=" + run.assignmentVersion);
            schedule(run);
            return status(run);
        }
    }

    private Delivery authorizeStart(Run run) {
        Delivery d = deliveries.findById(run.deliveryId).orElseThrow();
        if (d.getLocationMode() != LocationMode.SIMULATED_ROUTE) throw ApiException.conflict("NOT_SIMULATED_DELIVERY", "SIMULATED_ROUTE 배송 아님");
        if (!run.riderId.equals(d.getRiderId())) {
            throw ApiException.conflict("RIDER_MISMATCH", "시나리오 라이더(" + run.riderId + ")가 현재 배정 라이더(" + d.getRiderId() + ")와 다릅니다");
        }
        if (!STARTABLE.contains(d.getStatus())) {
            throw ApiException.conflict("HANDOVER_NOT_COMPLETED", "MFC 인계(PICKED_UP) 이후에만 시작할 수 있습니다. 현재 " + d.getStatus());
        }
        return d;
    }

    public Status pause(String id) {
        Run run = get(id);
        synchronized (run) {
            if (run.state == State.PAUSED) throw ApiException.conflict("ALREADY_PAUSED", "이미 일시정지 상태입니다");
            requireState(run, State.RUNNING);
            cancelFuture(run);
            run.state = State.PAUSED;
            log(run, "PAUSED", "");
            return status(run);
        }
    }

    public Status resume(String id) {
        Run run = get(id);
        synchronized (run) {
            if (run.state == State.RUNNING) throw ApiException.conflict("ALREADY_RUNNING", "이미 실행 중입니다");
            requireState(run, State.PAUSED);
            Delivery d = authorizeStart(run);
            if (d.getAssignmentVersion() != run.assignmentVersion) throw ApiException.conflict("ASSIGNMENT_CHANGED", "배정이 바뀌어 재개할 수 없습니다");
            run.state = State.RUNNING;
            log(run, "RESUMED", "");
            schedule(run);
            return status(run);
        }
    }

    public Status setRate(String id, int rate) {
        if (!RATES.contains(rate)) throw ApiException.badRequest("INVALID_RATE", "배속은 1, 5, 10 만 허용");
        Run run = get(id);
        synchronized (run) {
            requireState(run, State.CREATED, State.READY, State.RUNNING, State.PAUSED);
            run.rate = rate;
            log(run, "RATE_CHANGED", rate + "x");
            return status(run);
        }
    }

    public Status stop(String id, String reason) {
        Run run = get(id);
        synchronized (run) {
            if (run.state == State.STOPPED) return status(run);
            if (run.state == State.FINISHED && run.dwelling) {
                endDwell(run, "수동 종료");
                return status(run);
            }
            requireState(run, State.CREATED, State.READY, State.RUNNING, State.PAUSED);
            terminate(run, State.STOPPED, reason == null ? "수동 종료" : reason);
            return status(run);
        }
    }

    /** 종료/실패/완주 또는 READY 상태를 처음(READY)으로 되돌린다. 실행 중이면 먼저 종료해야 한다. */
    public Status reset(String id) {
        Run run = get(id);
        synchronized (run) {
            if (run.state == State.RUNNING || run.state == State.PAUSED) {
                throw ApiException.conflict("STOP_FIRST", "실행/일시정지 중에는 초기화할 수 없습니다. 먼저 종료하세요");
            }
            String other = activeByDelivery.get(run.deliveryId);
            if (other != null && !other.equals(id)) throw ApiException.conflict("DUPLICATE_RUN", "같은 배송에 다른 활성 시나리오가 있습니다: " + other);
            cancelFuture(run);
            run.ticks = run.generated = run.sent = run.acceptedLatest = run.history = run.duplicate = run.rejected = run.stale = 0;
            run.dwelling = false; run.dwellUntil = null; run.dwellSent = 0;
            run.lastSentAt = null; run.lastPosition = null; run.lastError = null; run.startedAt = null; run.assignmentVersion = null;
            run.connected = true;
            run.state = run.route == null ? State.CREATED : State.READY;
            if (run.route != null) run.playback = newPlayback(run);
            activeByDelivery.put(run.deliveryId, id);
            log(run, "RESET", "→ " + run.state);
            return status(run);
        }
    }

    public void delete(String id) {
        Run run = get(id);
        synchronized (run) {
            cancelFuture(run);
            if (ACTIVE.contains(run.state)) run.state = State.STOPPED;
            activeByDelivery.remove(run.deliveryId, id);
            runs.remove(id);
            log(run, "DELETED", "");
        }
    }

    public Status injectFault(String id, FaultSpec.Type type, Integer durationTicks, Integer durationSeconds) {
        Run run = get(id);
        synchronized (run) {
            requireState(run, State.RUNNING, State.PAUSED);
            run.playback.injectNow(type, durationTicks, durationSeconds);
            log(run, "FAULT_SCHEDULED_NOW", type + " ticks=" + durationTicks + " sec=" + durationSeconds);
            return status(run);
        }
    }

    // ------------------------------------------------------------------ 재생

    private void schedule(Run run) {
        if (!autoTick) return;
        cancelFuture(run);
        run.future = executor.scheduleAtFixedRate(() -> {
            try {
                if (run.state == State.FINISHED) dwellTick(run.scenarioId);
                else tick(run.scenarioId);
            } catch (RuntimeException e) {
                log.warn("시뮬레이션 틱 오류 {}: {}", run.scenarioId, e.getMessage());
            }
        }, tickRealMs, tickRealMs, TimeUnit.MILLISECONDS);
    }

    /** 1틱 실행 (자동 스케줄러 또는 테스트가 직접 호출). 실제 시간 tickRealMs × 배속 만큼 가상 시간이 흐른다. */
    public Status tick(String id) {
        Run run = get(id);
        synchronized (run) {
            if (run.state != State.RUNNING) return status(run);
            run.ticks++;
            RoutePlayback.TickOutput out = run.playback.tick(tickRealMs * run.rate);
            run.generated++;
            run.connected = out.connected();
            for (String ev : out.events()) log(run, "ENGINE", ev);
            if (!out.toSend().isEmpty()) {
                Instant virtualNow = run.virtualEpoch.plusMillis(out.virtualMs());
                List<LocationPoint> pts = new ArrayList<>();
                for (RoutePlayback.Emission e : out.toSend()) {
                    pts.add(new LocationPoint(e.seq(), e.lat(), e.lng(), e.accuracyMeters(), null, null, run.virtualEpoch.plusMillis(e.virtualMs())));
                }
                try {
                    var res = injector.inject(run.scenarioId, run.deliveryId, run.riderId, run.assignmentVersion, virtualNow, pts);
                    run.sent += pts.size();
                    run.lastSentAt = clock.instant();
                    for (var pr : res.points()) {
                        switch (pr.verdict()) {
                            case ACCEPTED_LATEST -> run.acceptedLatest++;
                            case ACCEPTED_HISTORY -> run.history++;
                            case DUPLICATE -> run.duplicate++;
                            case STALE -> run.stale++;
                            default -> run.rejected++;
                        }
                        if (pr.verdict() != LocationVerdict.ACCEPTED_LATEST) log(run, "VERDICT", "seq=" + pr.seq() + " " + pr.verdict() + " " + pr.reason());
                    }
                    RoutePlayback.Emission last = out.toSend().get(out.toSend().size() - 1);
                    run.lastPosition = new double[] {last.lat(), last.lng()};
                    log(run, "SENT", "count=" + pts.size() + " seqs=" + pts.stream().map(p -> String.valueOf(p.seq())).toList()
                            + String.format(" progress=%.0f/%.0fm", out.progressedMeters(), run.route.totalMeters()));
                } catch (ApiException e) {
                    // 권한 회수(재배정·취소·완료·모드 불일치): 즉시 중단
                    terminate(run, State.FAILED, "주입 거부 " + e.code() + ": " + e.getMessage());
                    return status(run);
                }
            }
            if (out.finished()) {
                // 재생은 끝났지만(FINISHED) 스케줄은 유지해 배송지 정차 위치를 계속 보낸다(dwellTick).
                run.state = State.FINISHED;
                activeByDelivery.remove(run.deliveryId, run.scenarioId);
                run.dwelling = true;
                run.dwellUntil = clock.instant().plus(DWELL_MAX);
                log(run, "FINISHED", "경로 끝 도달 — 배송 완료 아님. 배송지 정차 위치 전송 시작(최대 " + DWELL_MAX.toMinutes()
                        + "분, 완료·재배정·취소 시 중단). 라이더 앱에서 고객 도착·증빙 완료 필요");
            }
            return status(run);
        }
    }

    /**
     * FINISHED 이후 배송지 정차 위치 1건 전송 (자동 스케줄러 또는 테스트가 직접 호출).
     * 고객 도착 판정은 최신 위치가 30초 이내여야 하므로, 정차한 실제 라이더처럼 위치를 계속 보낸다.
     * 위치 수신이 거부되면(완료·취소·재배정) 즉시 중단한다. 도착 판정을 우회하지 않는다 — 같은 수집·검증 경로를 지난다.
     */
    public Status dwellTick(String id) {
        Run run = get(id);
        synchronized (run) {
            if (run.state != State.FINISHED || !run.dwelling) {
                if (run.state == State.FINISHED) cancelFuture(run);
                return status(run);
            }
            if (clock.instant().isAfter(run.dwellUntil)) {
                endDwell(run, "정차 위치 전송 최대 시간 초과");
                return status(run);
            }
            RoutePlayback.Emission e = run.playback.dwell(tickRealMs * run.rate);
            Instant virtualNow = run.virtualEpoch.plusMillis(e.virtualMs());
            try {
                injector.inject(run.scenarioId, run.deliveryId, run.riderId, run.assignmentVersion, virtualNow,
                        List.of(new LocationPoint(e.seq(), e.lat(), e.lng(), e.accuracyMeters(), null, null, virtualNow)));
                run.dwellSent++;
                run.lastSentAt = clock.instant();
                run.lastPosition = new double[] {e.lat(), e.lng()};
            } catch (ApiException ex) {
                endDwell(run, "주입 거부 " + ex.code() + " → 정차 위치 전송 중단");
            }
            return status(run);
        }
    }

    private void endDwell(Run run, String reason) {
        run.dwelling = false;
        cancelFuture(run);
        log(run, "DWELL_END", reason);
    }

    /** 배송 상태 변경(취소·완료·재배정) 커밋 후 활성 재생기 권한 회수. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeliveryChanged(DeliveryChanged ev) {
        String id = activeByDelivery.get(ev.deliveryId());
        if (id == null) return;
        Run run = runs.get(id);
        if (run == null) return;
        Delivery d = deliveries.findById(ev.deliveryId()).orElse(null);
        if (d == null) return;
        synchronized (run) {
            if (run.state != State.RUNNING && run.state != State.PAUSED) return;
            if (d.getStatus().isTerminal()) {
                terminate(run, d.getStatus() == DeliveryStatus.DELIVERED ? State.STOPPED : State.FAILED, "배송 " + d.getStatus() + " → 위치 주입 중단");
            } else if (!run.riderId.equals(d.getRiderId()) || (run.assignmentVersion != null && run.assignmentVersion != d.getAssignmentVersion())) {
                terminate(run, State.FAILED, "라이더 재배정 → 주입 권한 회수");
            }
        }
    }

    private void terminate(Run run, State s, String reason) {
        cancelFuture(run);
        run.state = s;
        if (s == State.FAILED) run.lastError = reason;
        activeByDelivery.remove(run.deliveryId, run.scenarioId);
        log(run, s.name(), reason);
    }

    private void fail(Run run, String reason) {
        run.state = State.FAILED;
        run.lastError = reason;
        activeByDelivery.remove(run.deliveryId, run.scenarioId);
        log(run, "FAILED", reason);
    }

    private void cancelFuture(Run run) {
        if (run.future != null) {
            run.future.cancel(false);
            run.future = null;
        }
    }

    // ------------------------------------------------------------------ 조회

    public Collection<Status> list() { return runs.values().stream().map(this::status).toList(); }

    public Status status(String id) { return status(get(id)); }

    public RoutePreview route(String id) {
        Run run = get(id);
        if (run.route == null) throw ApiException.conflict("ROUTE_NOT_LOADED", "먼저 검증/미리보기를 실행하세요");
        List<double[]> pts = run.route.points().stream().map(p -> new double[] {p.lat(), p.lng()}).toList();
        return new RoutePreview(run.route.source().name(), run.route.routeRef(), pts.size(), run.route.totalMeters(),
                run.route.providerDurationSeconds(), run.route.fetchedAt(), run.route.note(), pts);
    }

    public List<RouteProviders.Fixture> fixtures() { return routes.fixtures(); }

    public boolean hasActiveRun(String deliveryId) { return activeByDelivery.containsKey(deliveryId); }

    private Status status(Run run) {
        RoutePlayback pb = run.playback;
        double total = run.route == null ? 0 : run.route.totalMeters();
        String ds = deliveries.findById(run.deliveryId).map(d -> d.getStatus().name()).orElse("UNKNOWN");
        List<LogEntry> recent;
        synchronized (run.recent) { recent = new ArrayList<>(run.recent); }
        String label = "SIMULATED / " + (run.route == null ? run.source : run.route.source());
        return new Status(run.scenarioId, run.deliveryId, run.riderId, run.state, run.source.name(), run.fixtureId, run.rate,
                pb == null ? run.requestedSpeed : pb.speedMps(), run.seed, pb == null ? 0 : pb.distance(), total,
                pb == null ? 0 : pb.progressFraction(), pb == null ? 0 : pb.virtualMs(), run.createdAt, run.startedAt, run.lastSentAt,
                run.lastPosition, run.connected, pb != null && pb.stopped(), run.lastError, ds,
                new Counters(run.ticks, run.generated, run.sent, run.acceptedLatest, run.history, run.duplicate, run.rejected, run.stale,
                        run.dwellSent),
                run.faults, recent, label, run.dwelling);
    }

    private Run get(String id) {
        Run r = runs.get(id);
        if (r == null) throw ApiException.notFound("SCENARIO_NOT_FOUND", "시나리오 없음: " + id);
        return r;
    }

    private void requireState(Run run, State... allowed) {
        for (State s : allowed) if (run.state == s) return;
        throw ApiException.conflict("INVALID_SIMULATION_STATE", "현재 상태 " + run.state + " 에서는 이 명령을 실행할 수 없습니다");
    }

    // ------------------------------------------------------------------ 증거 로그 (JSONL)

    private void log(Run run, String type, String detail) {
        long vms = run.playback == null ? 0 : run.playback.virtualMs();
        LogEntry e = new LogEntry(clock.instant(), vms, type, detail);
        synchronized (run.recent) {
            run.recent.addLast(e);
            while (run.recent.size() > 60) run.recent.removeFirst();
        }
        try {
            Files.createDirectories(logDir);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scenarioId", run.scenarioId);
            m.put("deliveryId", run.deliveryId);
            m.put("locationSource", "SIMULATED_ROUTE");
            m.put("routeSource", run.route == null ? run.source.name() : run.route.source().name());
            m.put("realAt", e.realAt().toString());
            m.put("virtualMs", vms);
            m.put("type", type);
            m.put("detail", detail);
            Files.writeString(logDir.resolve(run.scenarioId + ".jsonl"), json.writeValueAsString(m) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.debug("시뮬레이션 로그 기록 실패: {}", ex.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        runs.values().forEach(this::cancelFuture);
        executor.shutdownNow();
    }
}
