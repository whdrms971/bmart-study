package com.baropick.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.baropick.delivery.retention.RetentionService;
import com.baropick.delivery.support.IntegrationTestBase;

/** P-09 보관 기간: 위치 이력 30일, 증빙 사진 90일 — 지난 데이터 자동 삭제, 기록·상태 이력은 유지. */
class RetentionIT extends IntegrationTestBase {

    @Autowired RetentionService retention;

    private int count(String sql, Object... args) { return jdbcTemplate.queryForObject(sql, Integer.class, args); }

    @Test
    @DisplayName("P-09 31일 후 위치 이력만 삭제(최신 위치·사진 유지) → 91일 후 사진 파일 삭제(증빙 기록·해시·상태 이력 유지)")
    void purgeByAge() throws Exception {
        String d = remoteDelivery("C-TEST-01", 1);
        pickUp(d, "R-TEST-01");
        assertThat(post("R-TEST-01", "/api/rider/deliveries/" + d + "/start", null).status()).isEqualTo(200);
        assertThat(arriveHere("R-TEST-01", d).status()).isEqualTo(200);
        assertThat(complete("R-TEST-01", d, "ret-1", fakeJpeg()).status()).isEqualTo(200);

        String photo = jdbcTemplate.queryForObject("select photo_path from delivery_proof where delivery_id=?", String.class, d);
        assertThat(Files.exists(Path.of(photo))).isTrue();
        int historyBefore = count("select count(*) from delivery_status_history where delivery_id=?", d);
        assertThat(count("select count(*) from rider_location where delivery_id=?", d)).isPositive();

        // 29일: 아무것도 지우지 않음
        mclock().advance(Duration.ofDays(29));
        var r29 = retention.purgeNow();
        assertThat(r29.locationRowsDeleted()).isZero();
        assertThat(count("select count(*) from rider_location where delivery_id=?", d)).isPositive();

        // 31일: 위치 이력 삭제, 최신 위치·사진·기록 유지
        mclock().advance(Duration.ofDays(2));
        var r31 = retention.purgeNow();
        assertThat(r31.locationRowsDeleted()).isPositive();
        assertThat(count("select count(*) from rider_location where delivery_id=?", d)).isZero();
        assertThat(count("select count(*) from delivery_latest_location where delivery_id=?", d)).isEqualTo(1);
        assertThat(Files.exists(Path.of(photo))).isTrue();
        assertThat(r31.photosDeleted()).isZero();

        // 91일: 사진 파일 삭제, 증빙 기록(해시·크기)·상태 이력 유지, 다시 실행해도 중복 처리 없음
        mclock().advance(Duration.ofDays(60));
        var r91 = retention.purgeNow();
        assertThat(r91.photosDeleted()).isEqualTo(1);
        assertThat(Files.exists(Path.of(photo))).isFalse();
        var proof = jdbcTemplate.queryForMap("select photo_sha256, photo_bytes, photo_purged_at from delivery_proof where delivery_id=?", d);
        assertThat(proof.get("photo_sha256")).isNotNull();
        assertThat(((Number) proof.get("photo_bytes")).longValue()).isPositive();
        assertThat(proof.get("photo_purged_at")).isNotNull();
        assertThat(count("select count(*) from delivery_status_history where delivery_id=?", d)).isEqualTo(historyBefore);
        assertThat(status(d)).isEqualTo("DELIVERED");
        assertThat(retention.purgeNow().photosDeleted()).isZero();
    }
}
