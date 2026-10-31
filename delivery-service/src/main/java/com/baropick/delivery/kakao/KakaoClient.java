package com.baropick.delivery.kakao;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 카카오 Local(주소/키워드 검색)·카카오모빌리티 Directions 서버 측 클라이언트.
 * - 키는 Authorization 헤더로만 전송하고 로그·예외 메시지·응답에 포함하지 않는다.
 * - 호출 횟수를 API 별로 집계해 관리자 화면에서 비용/쿼터 관측에 쓴다.
 * - 응답을 영구 저장하지 않는다(재사용 조건 미확인, DECISIONS S-11).
 */
@Component
public class KakaoClient {
    private static final Logger log = LoggerFactory.getLogger(KakaoClient.class);

    public static class KakaoApiException extends RuntimeException {
        private final String code;
        public KakaoApiException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public record LatLng(double lat, double lng) {}

    public record DirectionsResult(int distanceMeters, int durationSeconds, List<LatLng> vertexes, String transId, Instant fetchedAt) {}

    public record Place(String name, String address, String roadAddress, double lat, double lng) {}

    public record Usage(String api, long calls, long failures, String lastStatus, Instant lastCallAt) {}

    private final KakaoProperties props;
    private final JsonMapper json;
    private final Clock clock;
    private final RestClient http;
    private final Map<String, AtomicLong[]> counters = new ConcurrentHashMap<>();
    private final Map<String, String> lastStatus = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastCall = new ConcurrentHashMap<>();

    public KakaoClient(KakaoProperties props, JsonMapper json, Clock clock) {
        this.props = props;
        this.json = json;
        this.clock = clock;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(props.connectTimeoutMs() == null ? 3000 : props.connectTimeoutMs()));
        f.setReadTimeout(Duration.ofMillis(props.readTimeoutMs() == null ? 5000 : props.readTimeoutMs()));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    public boolean configured() { return props.hasKey(); }

    /** 자동차 길찾기. summary=false 로 도로 vertex 포함. 좌표 순서는 경도,위도. */
    public DirectionsResult directions(double originLat, double originLng, double destLat, double destLng) {
        String url = String.format(Locale.ROOT, "%s/v1/directions?origin=%.7f,%.7f&destination=%.7f,%.7f&priority=RECOMMEND&summary=false",
                props.mobilityBaseUrl(), originLng, originLat, destLng, destLat);
        JsonNode root = call("directions", url);
        JsonNode routes = root.path("routes");
        if (!routes.isArray() || routes.isEmpty()) throw fail("directions", "ROUTE_EMPTY", "경로 응답이 비어 있습니다");
        JsonNode r = routes.get(0);
        int resultCode = r.path("result_code").asInt(-1);
        if (resultCode != 0) {
            throw fail("directions", "ROUTE_NOT_FOUND", "길찾기 실패 result_code=" + resultCode + " " + r.path("result_msg").asString(""));
        }
        List<LatLng> pts = new ArrayList<>();
        for (JsonNode s : r.path("sections")) {
            for (JsonNode road : s.path("roads")) {
                JsonNode v = road.path("vertexes");
                if (v.size() % 2 != 0) throw fail("directions", "ROUTE_MALFORMED", "vertexes 좌표쌍 개수 오류");
                for (int i = 0; i + 1 < v.size(); i += 2) {
                    double lng = v.get(i).asDouble(), lat = v.get(i + 1).asDouble();
                    if (lat < -90 || lat > 90 || lng < -180 || lng > 180) throw fail("directions", "ROUTE_MALFORMED", "좌표 범위 오류");
                    LatLng p = new LatLng(lat, lng);
                    if (pts.isEmpty() || !pts.get(pts.size() - 1).equals(p)) pts.add(p);
                }
            }
        }
        JsonNode summary = r.path("summary");
        if (pts.size() < 2) throw fail("directions", "ROUTE_MALFORMED", "경로 좌표가 2개 미만입니다");
        return new DirectionsResult(summary.path("distance").asInt(), summary.path("duration").asInt(), pts,
                root.path("trans_id").asString(null), clock.instant());
    }

    /** 키워드 검색 우선, 결과 없으면 주소 검색. */
    public List<Place> searchPlaces(String query) {
        String q = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        List<Place> out = new ArrayList<>();
        JsonNode kw = call("local.keyword", props.localBaseUrl() + "/v2/local/search/keyword.json?size=10&query=" + q);
        for (JsonNode d : kw.path("documents")) {
            out.add(new Place(d.path("place_name").asString(""), d.path("address_name").asString(""), d.path("road_address_name").asString(""),
                    d.path("y").asDouble(), d.path("x").asDouble()));
        }
        if (out.isEmpty()) {
            JsonNode ad = call("local.address", props.localBaseUrl() + "/v2/local/search/address.json?size=10&query=" + q);
            for (JsonNode d : ad.path("documents")) {
                JsonNode road = d.path("road_address");
                out.add(new Place(d.path("address_name").asString(""), d.path("address_name").asString(""),
                        road.isObject() ? road.path("address_name").asString("") : "", d.path("y").asDouble(), d.path("x").asDouble()));
            }
        }
        return out;
    }

    private JsonNode call(String api, String url) {
        if (!props.hasKey()) throw fail(api, "KAKAO_KEY_MISSING", "KAKAO_REST_API_KEY 가 서버에 설정되지 않았습니다");
        AtomicLong[] c = counters.computeIfAbsent(api, k -> new AtomicLong[] {new AtomicLong(), new AtomicLong()});
        c[0].incrementAndGet();
        lastCall.put(api, clock.instant());
        try {
            String body = http.get().uri(java.net.URI.create(url)).header("Authorization", "KakaoAK " + props.restApiKey())
                    .retrieve().body(String.class);
            lastStatus.put(api, "200");
            return json.readTree(body == null ? "{}" : body);
        } catch (HttpStatusCodeException e) {
            c[1].incrementAndGet();
            int sc = e.getStatusCode().value();
            lastStatus.put(api, String.valueOf(sc));
            String code = switch (sc) {
                case 401 -> "KAKAO_KEY_INVALID";
                case 403 -> "KAKAO_PERMISSION_DENIED";
                case 429 -> "KAKAO_QUOTA_EXCEEDED";
                default -> "KAKAO_HTTP_" + sc;
            };
            // 응답 본문의 errorType/message 만 노출 (키 미포함)
            String detail = safeDetail(e.getResponseBodyAsString());
            log.warn("카카오 API 오류 api={} status={} detail={}", api, sc, detail);
            throw new KakaoApiException(code, "카카오 API 오류(" + sc + ") " + detail);
        } catch (RestClientException e) {
            c[1].incrementAndGet();
            lastStatus.put(api, "IO_ERROR");
            log.warn("카카오 API 통신 실패 api={} error={}", api, e.getClass().getSimpleName());
            throw new KakaoApiException("KAKAO_UNREACHABLE", "카카오 API 통신 실패: " + e.getClass().getSimpleName());
        }
    }

    private String safeDetail(String body) {
        try {
            JsonNode n = json.readTree(body);
            String t = n.path("errorType").asString(n.path("code").asString(""));
            String m = n.path("message").asString(n.path("msg").asString(""));
            return (t + " " + m).trim();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private KakaoApiException fail(String api, String code, String msg) {
        lastStatus.put(api, code);
        return new KakaoApiException(code, msg);
    }

    public List<Usage> usage() {
        List<Usage> out = new ArrayList<>();
        counters.forEach((k, v) -> out.add(new Usage(k, v[0].get(), v[1].get(), lastStatus.get(k), lastCall.get(k))));
        return out;
    }
}
