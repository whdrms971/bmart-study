import 'package:delivery_common/delivery_common.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const dest = GeoPoint(35.1621, 129.1602);
  // 위도 1도 ≈ 111,195m (R=6,371,008.8m)
  GeoPoint north(double m) => GeoPoint(dest.lat + m / 111195.0, dest.lng);

  test('300m 미만은 가까운 두 점(줌 16 고정), 이상은 두 점 맞춤', () {
    expect(closeEnough(north(4), dest), isTrue); // 배송지 도착 직전(오늘 에뮬레이터에서 과확대된 상황)
    expect(closeEnough(north(299), dest), isTrue);
    expect(closeEnough(north(301), dest), isFalse);
    expect(closeEnough(north(2000), dest), isFalse);
  });
}
