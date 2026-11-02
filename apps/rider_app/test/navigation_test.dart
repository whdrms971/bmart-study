import 'package:delivery_common/delivery_common.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rider_app/navigation.dart';

/// 북쪽으로 1,000m → 동쪽으로 1,000m 가는 ㄱ자 경로 (꺾이는 곳에서 우회전)
Map<String, dynamic> lRoute() {
  const lat0 = 35.0, lng0 = 129.0;
  const dLat = 1000 / 111195.0; // 1,000m 위도
  final dLng = 1000 / (111195.0 * 0.8191520443); // 위도 35도에서 1,000m 경도
  return {
    'target': 'DESTINATION',
    'distanceMeters': 2000,
    'durationSeconds': 600,
    'computedAt': '2026-11-02T07:00:00Z',
    'note': '카카오모빌리티 자동차 경로 기준',
    'cached': false,
    'points': [
      [lat0, lng0],
      [lat0 + dLat / 2, lng0],
      [lat0 + dLat, lng0],
      [lat0 + dLat, lng0 + dLng],
    ],
    'guides': [
      {'lat': lat0, 'lng': lng0, 'type': 100, 'guidance': '출발지', 'name': '출발지'},
      {'lat': lat0 + dLat, 'lng': lng0, 'type': 2, 'guidance': '우회전', 'name': '해운대로'},
      {'lat': lat0 + dLat, 'lng': lng0 + dLng, 'type': 101, 'guidance': '목적지', 'name': '목적지'},
    ],
  };
}

GeoPoint northOf(double meters, {double eastMeters = 0}) =>
    GeoPoint(35.0 + meters / 111195.0, 129.0 + eastMeters / (111195.0 * 0.8191520443));

void main() {
  final route = NavRoute.fromJson(lRoute());

  test('경로 길이·안내 지점 위치를 경로상 거리로 계산', () {
    expect(route.length, closeTo(2000, 2));
    expect(route.guides[1].along, closeTo(1000, 2));
    expect(route.guides[2].along, closeTo(2000, 2));
  });

  test('출발 직후: 다음 안내는 1,000m 앞 우회전, 남은 2.0km·약 10분 (출발지 안내는 건너뜀)', () {
    final p = route.progress(northOf(0));
    expect(p.next!.guidance, '우회전');
    expect(p.toNextMeters, closeTo(1000, 2));
    expect(p.instruction, '1.0km 앞 우회전 · 해운대로');
    expect(p.summary, '남은 2.0km · 약 10분');
    expect(p.offRoute, isFalse);
  });

  test('경로 위 진행: 700m 지점이면 300m 앞 우회전, 꺾은 뒤에는 목적지 안내', () {
    final p = route.progress(northOf(700));
    expect(p.instruction, '300m 앞 우회전 · 해운대로');
    expect(p.remainingMeters, closeTo(1300, 3));
    expect(p.remainingSeconds, closeTo(390, 2));
    final after = route.progress(northOf(1000, eastMeters: 400));
    expect(after.instruction, '600m 앞 목적지');
  });

  test('경로 옆 10m 는 이탈 아님, 80m 는 이탈', () {
    expect(route.progress(northOf(500, eastMeters: 10)).offRoute, isFalse);
    final off = route.progress(northOf(500, eastMeters: 80));
    expect(off.offRoute, isTrue);
    expect(off.offRouteMeters, closeTo(80, 1));
  });

  test('목적지 30m 안이면 "목적지 근처입니다"', () {
    expect(route.progress(northOf(1000, eastMeters: 985)).instruction, '목적지 근처입니다');
  });

  test('재탐색은 이탈 2회 연속일 때만 (한 번 튀는 위치로 유료 길찾기를 다시 부르지 않음)', () {
    final m = RerouteMonitor();
    final off = route.progress(northOf(500, eastMeters: 80));
    final on = route.progress(northOf(500));
    expect(m.shouldReroute(off), isFalse);
    expect(m.shouldReroute(on), isFalse);
    expect(m.shouldReroute(off), isFalse);
    expect(m.shouldReroute(off), isTrue);
  });

  test('거리 표시: 1km 미만은 10m 단위, 이상은 0.1km', () {
    expect(formatDistance(287), '290m');
    expect(formatDistance(1260), '1.3km');
  });
}
