import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:rider_app/arrival_check.dart';

const policy = ArrivalPolicyValues(maxDistanceMeters: 150, maxLocationAgeSeconds: 30, maxAccuracyMeters: 50);
const destLat = 35.1621, destLng = 129.1602;

/// 배송지에서 정북으로 meters 떨어진 위도 (서버 테스트와 같은 방식)
double northLat(double meters) => destLat + (meters / 6371008.8) * 180 / pi;

ArrivalEval eval(double meters, {double? accuracy = 5, int ageSeconds = 0}) {
  final now = DateTime.utc(2026, 11, 2, 12);
  return evaluateArrival(
    policy: policy, destLat: destLat, destLng: destLng, now: now,
    lat: northLat(meters), lng: destLng, accuracyMeters: accuracy, measuredAt: now.subtract(Duration(seconds: ageSeconds)),
  );
}

void main() {
  test('0 / 149 / 150m 허용', () {
    for (final m in [0.0, 149.0, 150.0]) {
      expect(eval(m).code, ArrivalCode.ok, reason: '${m}m');
    }
  });

  test('151 / 480m 거부', () {
    for (final m in [151.0, 480.0]) {
      final e = eval(m);
      expect(e.code, ArrivalCode.tooFar, reason: '${m}m');
      expect(e.distanceMeters, closeTo(m, 0.05));
    }
  });

  test('위치 없음 / 31초 경과 / 정확도 51m·없음 → 재측정', () {
    final now = DateTime.utc(2026, 11, 2, 12);
    expect(evaluateArrival(policy: policy, destLat: destLat, destLng: destLng, now: now).code, ArrivalCode.locationMissing);
    expect(eval(0, ageSeconds: 31).code, ArrivalCode.locationStale);
    expect(eval(0, ageSeconds: 30).code, ArrivalCode.ok);
    expect(eval(0, accuracy: 51).code, ArrivalCode.locationInaccurate);
    expect(eval(0, accuracy: 50).code, ArrivalCode.ok);
    expect(eval(0, accuracy: null).code, ArrivalCode.locationInaccurate);
    expect(eval(0, ageSeconds: 31).needsRemeasure, isTrue);
  });

  test('판정 순서: 오래된 위치는 거리보다 먼저 재측정 요청', () {
    expect(eval(480, ageSeconds: 60).code, ArrivalCode.locationStale);
  });

  test('서버 판정 변환', () {
    final e = fromServerCheck({'code': 'TOO_FAR', 'message': 'x', 'distanceMeters': 480.0, 'locationAgeSeconds': 3, 'accuracyMeters': 5.0});
    expect(e!.code, ArrivalCode.tooFar);
    expect(e.distanceMeters, 480.0);
    expect(fromServerCheck(null), isNull);
  });
}
