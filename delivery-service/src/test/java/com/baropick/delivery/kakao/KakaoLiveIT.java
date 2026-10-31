package com.baropick.delivery.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 카카오 API 호출 검증 (Mock 아님). 기본 test 에서 제외, `gradlew test -PkakaoLive` 로만 실행.
 * 호출 수: Directions 1회 + Local 키워드 1회(결과 없으면 주소 1회). 무료 쿼터 범위. 키는 출력하지 않는다.
 * 결과 요약(키 미포함)을 ../reports/kakao-live/ 에 남긴다.
 */
@Tag("kakao-live")
class KakaoLiveIT {

    @Test
    void realDirectionsAndLocalSearch() throws IOException {
        String key = System.getenv("KAKAO_REST_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "KAKAO_REST_API_KEY 미설정");
        KakaoProperties props = new KakaoProperties(key, "https://dapi.kakao.com", "https://apis-navi.kakaomobility.com", 3000, 8000);
        assertThat(props.toString()).doesNotContain(key);
        KakaoClient client = new KakaoClient(props, JsonMapper.builder().build(), Clock.systemUTC());
        StringBuilder report = new StringBuilder("# Kakao live check " + Instant.now() + "\n");

        // 가상 원거리 거점(MFC-TEST-REMOTE-01) → 테스트 배송지 T1
        try {
            var r = client.directions(35.169176, 129.132233, 35.162100, 129.160200);
            report.append("directions: OK distance=").append(r.distanceMeters()).append("m duration=").append(r.durationSeconds())
                    .append("s vertexes=").append(r.vertexes().size()).append(" transId=").append(r.transId()).append('\n');
            assertThat(r.vertexes().size()).isGreaterThan(2);
            assertThat(r.distanceMeters()).isPositive();
            var first = r.vertexes().get(0);
            assertThat(first.lat()).isBetween(35.0, 35.3);  // 경도·위도 순서 해석 확인
            assertThat(first.lng()).isBetween(129.0, 129.3);
        } catch (KakaoClient.KakaoApiException e) {
            report.append("directions: FAIL ").append(e.code()).append(' ').append(e.getMessage()).append('\n');
            write(report);
            throw e;
        }
        try {
            var places = client.searchPlaces("해운대역");
            report.append("local search: OK results=").append(places.size());
            if (!places.isEmpty()) report.append(" first=").append(places.get(0).name()).append(" (").append(places.get(0).lat()).append(',')
                    .append(places.get(0).lng()).append(')');
            report.append('\n');
            assertThat(places).isNotEmpty();
        } catch (KakaoClient.KakaoApiException e) {
            report.append("local search: FAIL ").append(e.code()).append(' ').append(e.getMessage()).append('\n');
            write(report);
            throw e;
        }
        report.append("usage: ").append(client.usage()).append('\n');
        assertThat(report.toString()).doesNotContain(key);
        write(report);
    }

    private static void write(StringBuilder sb) throws IOException {
        Path dir = Path.of("../reports/kakao-live");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("kakao-live-" + System.currentTimeMillis() + ".md"), sb.toString(), StandardCharsets.UTF_8);
    }
}
