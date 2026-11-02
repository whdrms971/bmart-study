import 'package:flutter_test/flutter_test.dart';
import 'package:rider_app/navigation_links.dart';

void main() {
  test('앱 링크는 목적지·자동차 경로만 넘긴다 (출발지는 카카오맵 앱의 실시간 위치)', () {
    expect(kakaoMapAppRoute(35.1621, 129.1602).toString(), 'kakaomap://route?ep=35.1621,129.1602&by=CAR');
  });

  test('웹 링크: 위치를 알면 출발지를 포함한다', () {
    final u = kakaoMapWebRoute(destName: '배송지', lat: 35.1621, lng: 129.1602, startLat: 35.1692, startLng: 129.1322).toString();
    expect(u, startsWith('https://map.kakao.com/link/from/'));
    expect(u, contains(',35.1692,129.1322/to/'));
    expect(u, endsWith(',35.1621,129.1602'));
    expect(Uri.decodeFull(u), contains('/to/배송지,'));
  });

  test('웹 링크: 위치를 모르면 목적지만 넘긴다', () {
    final u = kakaoMapWebRoute(destName: '배송지', lat: 35.1621, lng: 129.1602).toString();
    expect(Uri.decodeFull(u), 'https://map.kakao.com/link/to/배송지,35.1621,129.1602');
  });
}
