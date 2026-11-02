package com.baropick.delivery.retention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.domain.DeliveryProof;
import com.baropick.delivery.domain.DeliveryProofRepository;
import com.baropick.delivery.domain.RiderLocationRepository;

/**
 * P-09 보관 기간 (2026-11-02 결정: 위치 이력 30일, 증빙 사진 90일) — 지난 데이터 자동 삭제.
 * - 위치 이력(rider_location): 서버 수신 시각 기준 기간 경과분 삭제
 * - 증빙 사진: 파일만 삭제하고 증빙 기록(해시·크기·시각·방식)은 유지, photo_purged_at 기록
 * - 유지: 배송 상태 이력, 배송별 최신 위치, 증빙 기록, 도착 예외 감사 기록
 * 기간이 설정되지 않은 항목은 삭제하지 않는다. ※ 법정 보관·파기 의무는 법무 확인 대상(DECISIONS.md P-09).
 */
@Service
public class RetentionService {
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    public record PurgeResult(int locationRowsDeleted, int photosDeleted, int photosMissing, int photoErrors, Instant locationCutoff,
                              Instant photoCutoff) {}

    private final RiderLocationRepository locations;
    private final DeliveryProofRepository proofs;
    private final DeliveryProperties.Retention cfg;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RetentionService(RiderLocationRepository locations, DeliveryProofRepository proofs, DeliveryProperties props,
                            TransactionTemplate tx, Clock clock) {
        this.locations = locations;
        this.proofs = proofs;
        this.cfg = props.retention();
        this.tx = tx;
        this.clock = clock;
    }

    /** 매일 정해진 시각(기본 03:30, delivery.retention.purge-cron) 실행. purge-enabled=false 면 실행하지 않는다. */
    @Scheduled(cron = "${delivery.retention.purge-cron:0 30 3 * * *}", zone = "${delivery.policy.dispatch-time-zone:Asia/Seoul}")
    public void scheduledPurge() {
        if (!cfg.purgeEnabled()) return;
        PurgeResult r = purgeNow();
        log.info("보관 기간 정리: 위치 이력 {}건 삭제(기준 {}), 증빙 사진 {}건 삭제·{}건 파일 없음·{}건 오류(기준 {})",
                r.locationRowsDeleted(), r.locationCutoff(), r.photosDeleted(), r.photosMissing(), r.photoErrors(), r.photoCutoff());
    }

    public PurgeResult purgeNow() {
        Instant now = clock.instant();
        Instant locCutoff = cfg.locationHistoryDays() == null ? null : now.minus(Duration.ofDays(cfg.locationHistoryDays()));
        Instant photoCutoff = cfg.proofPhotoDays() == null ? null : now.minus(Duration.ofDays(cfg.proofPhotoDays()));

        int locDeleted = 0;
        if (locCutoff != null) {
            Integer n = tx.execute(s -> locations.deleteReceivedBefore(locCutoff));
            locDeleted = n == null ? 0 : n;
        }

        int deleted = 0, missing = 0, errors = 0;
        if (photoCutoff != null) {
            while (true) {
                var batch = proofs.findTop500ByCreatedAtBeforeAndPhotoPurgedAtIsNullOrderByCreatedAt(photoCutoff);
                if (batch.isEmpty()) break;
                int progressed = 0;
                for (DeliveryProof p : batch) {
                    try {
                        boolean existed = Files.deleteIfExists(Path.of(p.getPhotoPath()));
                        if (existed) deleted++; else missing++;
                        tx.executeWithoutResult(s -> proofs.findById(p.getId()).ifPresent(x -> x.markPhotoPurged(now)));
                        progressed++;
                    } catch (IOException | RuntimeException e) {
                        errors++;
                        log.warn("증빙 사진 삭제 실패 proof={} : {}", p.getId(), e.getClass().getSimpleName());
                    }
                }
                if (progressed == 0) break; // 전부 실패한 배치를 무한 반복하지 않는다
            }
        }
        return new PurgeResult(locDeleted, deleted, missing, errors, locCutoff, photoCutoff);
    }
}
