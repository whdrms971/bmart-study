package com.baropick.delivery.mfc;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.baropick.delivery.common.ApiException;
import com.baropick.delivery.common.Geo;
import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.config.DeliveryProperties.RadiusPolicy;
import com.baropick.delivery.domain.LocationMode;

/**
 * Delivery 가 배차·경로 계산에 쓰는 MFC 거점 정보(위치·위치 모드·배차 반경 정책).
 * 실제 거점 기준정보(BackOffice center-master)가 생기면 이 레지스트리를 그 조회 어댑터로 교체한다.
 */
@Component
public class MfcRegistry {

    public record MfcSite(String id, String name, boolean virtual, double latitude, double longitude,
                          LocationMode locationMode, RadiusPolicy radiusPolicy, Integer radiusMeters) {}

    private final Map<String, MfcSite> sites = new LinkedHashMap<>();

    public MfcRegistry(DeliveryProperties props) {
        for (var p : props.mfcs()) {
            if (p.id() == null || p.latitude() == null || p.longitude() == null || p.locationMode() == null || p.radiusPolicy() == null) {
                throw new IllegalStateException("MFC 설정 누락(id/latitude/longitude/location-mode/radius-policy): " + p.id());
            }
            // P-02: MFC 별 radius-meters 가 없으면 정책 기본값(delivery.policy.dispatch-radius-meters) 사용
            Integer radius = p.radiusMeters() != null ? p.radiusMeters() : props.policy().dispatchRadiusMeters();
            if (p.radiusPolicy() == RadiusPolicy.ENFORCE && radius == null) {
                throw new IllegalStateException("radius-policy=ENFORCE 인 MFC는 radius-meters 또는 delivery.policy.dispatch-radius-meters 가 필요합니다: " + p.id());
            }
            sites.put(p.id(), new MfcSite(p.id(), p.name(), p.virtual(), p.latitude(), p.longitude(), p.locationMode(),
                    p.radiusPolicy(), radius));
        }
    }

    public Optional<MfcSite> find(String id) { return Optional.ofNullable(sites.get(id)); }

    public MfcSite require(String id) {
        return find(id).orElseThrow(() -> ApiException.badRequest("UNKNOWN_MFC", "등록되지 않은 MFC: " + id));
    }

    public Collection<MfcSite> all() { return sites.values(); }

    /** local-test: 근거리 실GPS 테스트 거점을 실제 테스트 장소로 재설정. 위치 모드·정책은 바꾸지 않는다. */
    public synchronized MfcSite relocate(String id, double lat, double lng) {
        if (!Geo.validLat(lat) || !Geo.validLng(lng)) throw ApiException.badRequest("INVALID_COORDINATE", "좌표 범위 오류");
        MfcSite s = require(id);
        if (!s.virtual()) throw ApiException.forbidden("NOT_A_TEST_SITE", "테스트 거점만 재설정할 수 있습니다");
        MfcSite n = new MfcSite(s.id(), s.name(), s.virtual(), lat, lng, s.locationMode(), s.radiusPolicy(), s.radiusMeters());
        sites.put(id, n);
        return n;
    }
}
