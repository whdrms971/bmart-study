package com.baropick.delivery.testsupport.mockmfc;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Ids;
import com.baropick.delivery.handover.HandoverService;
import com.baropick.delivery.handover.MfcHandoverPort.HandoverCommand;
import com.baropick.delivery.handover.MfcHandoverPort.HandoverResult;
import com.baropick.delivery.handover.MfcHandoverPort.Outcome;
import com.baropick.delivery.testsupport.LocalServerClient;

/**
 * [Mock] MFC 현장 시스템. 계약만 흉내낸다: 포장 OPEN→SEALED→HANDED_OVER/VOIDED, 인계 시 재고 차감 "사실"만 기록.
 * 실제 MFC 재고·피킹 로직은 구현하지 않는다. 결과는 반드시 "Mock MFC" 로 보고한다.
 */
@Service
@Profile("local-test")
public class MockMfcService {
    public static final String SOURCE = "MFC-MOCK";

    public enum HandoverFault { NONE, REJECT_NEXT, TIMEOUT_AFTER_COMMIT_NEXT }

    public record MockPackage(String packageId, String deliveryId, String orderId, String mfcId, String status, String qrCode,
                              String handoverId, String handedOverRider, Instant handedOverAt, String executionId) {}

    public record StopResult(String deliveryId, Map<String, String> perPackage, boolean allStopped) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LocalServerClient server;
    private final Clock clock;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile HandoverFault fault = HandoverFault.NONE;
    private volatile long timeoutSleepMs = 6000;

    public MockMfcService(JdbcTemplate jdbc, TransactionTemplate tx, LocalServerClient server, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.server = server;
        this.clock = clock;
    }

    private Object lock(String deliveryId) { return locks.computeIfAbsent(deliveryId, k -> new Object()); }

    public void setFault(HandoverFault f) { this.fault = f; }
    public HandoverFault fault() { return fault; }
    public void setTimeoutSleepMs(long ms) { this.timeoutSleepMs = ms; }

    /** OMS 이행 요청 수신 → 포장 생성(OPEN). */
    public List<MockPackage> createPackages(String deliveryId, String orderId, String mfcId, int count) {
        synchronized (lock(deliveryId)) {
            return tx.execute(s -> {
                Integer existing = jdbc.queryForObject("select count(*) from mock_mfc_package where delivery_id=?", Integer.class, deliveryId);
                if (existing != null && existing > 0) return packages(deliveryId);
                Instant now = clock.instant();
                for (int i = 1; i <= count; i++) {
                    String pid = "PKG-" + deliveryId.substring(4) + "-" + i;
                    jdbc.update("insert into mock_mfc_package(package_id, delivery_id, order_id, mfc_id, status, qr_token, created_at) values (?,?,?,?,?,?,?)",
                            pid, deliveryId, orderId, mfcId, "OPEN", Ids.randomToken(8), Timestamp.from(now));
                }
                return packages(deliveryId);
            });
        }
    }

    /** 포장 완료(SEALED) 후 Delivery 에 PackagesReady 통지. */
    public Object seal(String deliveryId) {
        List<String> ids;
        synchronized (lock(deliveryId)) {
            ids = tx.execute(s -> {
                List<MockPackage> ps = packages(deliveryId);
                if (ps.isEmpty()) throw ApiException.notFound("NO_PACKAGES", "포장이 없습니다");
                if (ps.stream().anyMatch(p -> p.status().equals("VOIDED"))) throw ApiException.conflict("PACKAGE_VOIDED", "중지된 포장이 있습니다");
                jdbc.update("update mock_mfc_package set status='SEALED' where delivery_id=? and status='OPEN'", deliveryId);
                return ps.stream().map(MockPackage::packageId).toList();
            });
        }
        return server.post("/internal/mfc-events/packages-ready").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sourceSystem", SOURCE, "eventId", "PKR-" + deliveryId, "deliveryId", deliveryId, "packageIds", ids))
                .retrieve().body(Map.class);
    }

    /** 인계 허가+확정 (AuthorizePackageHandover + CommitPackageHandover 를 단순화). executionId 멱등. */
    public HandoverResult handover(HandoverCommand cmd) {
        HandoverFault f = this.fault;
        HandoverResult r;
        synchronized (lock(cmd.deliveryId())) {
            r = tx.execute(s -> doHandover(cmd, f));
        }
        if (f != HandoverFault.NONE) this.fault = HandoverFault.NONE;
        if (f == HandoverFault.TIMEOUT_AFTER_COMMIT_NEXT && r.outcome() == Outcome.HANDED_OVER) {
            // 확정은 했으나 응답이 늦어 Delivery 측에서는 결과 불명이 되는 상황 재현
            try { Thread.sleep(timeoutSleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return r;
    }

    private HandoverResult doHandover(HandoverCommand cmd, HandoverFault f) {
        var prior = byExecution(cmd.executionId());
        if (prior != null) return prior;
        List<MockPackage> rows = jdbc.query("select * from mock_mfc_package where package_id=?", (rs, i) -> map(rs), cmd.packageId());
        if (rows.isEmpty()) return reject(cmd, "PACKAGE_NOT_FOUND", "MFC에 없는 포장");
        MockPackage p = rows.get(0);
        if (!p.deliveryId().equals(cmd.deliveryId())) return reject(cmd, "PACKAGE_NOT_IN_DELIVERY", "다른 배송의 포장");
        if (!p.qrCode().equals(cmd.qrCode())) return reject(cmd, "QR_TOKEN_MISMATCH", "라벨이 무효이거나 위조되었습니다");
        if (f == HandoverFault.REJECT_NEXT) return reject(cmd, "MFC_HOLD", "[장애 주입] MFC 인계 보류");
        switch (p.status()) {
            case "VOIDED": return reject(cmd, "INVALID_STATE", "출고 중지된 포장입니다");
            case "OPEN": return reject(cmd, "PACKAGE_NOT_SEALED", "포장이 완료되지 않았습니다");
            case "HANDED_OVER": return reject(cmd, "ALREADY_HANDED_OVER", "이미 인계된 포장입니다");
            default: break;
        }
        Instant now = clock.instant();
        String hid = Ids.next("HOV");
        jdbc.update("update mock_mfc_package set status='HANDED_OVER', handover_id=?, handed_over_rider=?, handed_over_at=?, execution_id=? where package_id=?",
                hid, cmd.riderId(), Timestamp.from(now), cmd.executionId(), cmd.packageId());
        return new HandoverResult(Outcome.HANDED_OVER, cmd.packageId(), hid, now, null, "인계 확정 (Mock MFC: 이 시점에 현물·예약 차감 사실 기록)");
    }

    private HandoverResult reject(HandoverCommand cmd, String code, String msg) {
        return new HandoverResult(Outcome.REJECTED, cmd.packageId(), null, null, code, msg);
    }

    public HandoverResult byExecution(String executionId) {
        List<MockPackage> rows = jdbc.query("select * from mock_mfc_package where execution_id=?", (rs, i) -> map(rs), executionId);
        if (rows.isEmpty()) return null;
        MockPackage p = rows.get(0);
        return new HandoverResult(Outcome.HANDED_OVER, p.packageId(), p.handoverId(), p.handedOverAt(), null, "기존 실행 결과");
    }

    public HandoverResult query(String executionId) {
        HandoverResult r = byExecution(executionId);
        return r != null ? r : new HandoverResult(Outcome.NOT_FOUND, null, null, null, "NOT_FOUND", "해당 실행 없음");
    }

    /** OMS 취소 시 출고 중지(StopWork). 포장별 STOPPED / HANDED_OVER 판정. */
    public StopResult stop(String deliveryId) {
        synchronized (lock(deliveryId)) {
            return tx.execute(s -> {
                Map<String, String> per = new java.util.LinkedHashMap<>();
                for (MockPackage p : packages(deliveryId)) {
                    if (p.status().equals("HANDED_OVER")) {
                        per.put(p.packageId(), "HANDED_OVER");
                    } else {
                        jdbc.update("update mock_mfc_package set status='VOIDED' where package_id=?", p.packageId());
                        per.put(p.packageId(), "STOPPED");
                    }
                }
                return new StopResult(deliveryId, per, per.values().stream().allMatch("STOPPED"::equals));
            });
        }
    }

    public List<MockPackage> packages(String deliveryId) {
        return jdbc.query("select * from mock_mfc_package where delivery_id=? order by package_id", (rs, i) -> map(rs), deliveryId);
    }

    public List<MockPackage> all() {
        return jdbc.query("select * from mock_mfc_package order by created_at desc limit 300", (rs, i) -> map(rs));
    }

    private static MockPackage map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp("handed_over_at");
        String qr = HandoverService.QR_PREFIX + "|" + rs.getString("package_id") + "|" + rs.getString("qr_token");
        return new MockPackage(rs.getString("package_id"), rs.getString("delivery_id"), rs.getString("order_id"), rs.getString("mfc_id"),
                rs.getString("status"), qr, rs.getString("handover_id"), rs.getString("handed_over_rider"), t == null ? null : t.toInstant(),
                rs.getString("execution_id"));
    }

    public List<String> packageIds(String deliveryId) {
        List<String> out = new ArrayList<>();
        for (MockPackage p : packages(deliveryId)) out.add(p.packageId());
        return out;
    }
}
