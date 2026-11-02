import 'dart:math';

/// 고객 도착 사전 판정 (앱). 서버 ArrivalPolicy 와 같은 규칙·순서로 판정하며, 최종 판정은 서버가 다시 한다.
/// 순서: 위치 없음 → 측정 후 경과 초과 → 정확도 초과/없음 → 거리 초과. 거리는 0.01m 반올림 후 비교.
class ArrivalPolicyValues {
  const ArrivalPolicyValues({required this.maxDistanceMeters, required this.maxLocationAgeSeconds, required this.maxAccuracyMeters});

  final double maxDistanceMeters;
  final int maxLocationAgeSeconds;
  final double maxAccuracyMeters;

  static ArrivalPolicyValues? fromJson(Map<String, dynamic>? a) {
    if (a == null) return null;
    return ArrivalPolicyValues(
      maxDistanceMeters: (a['maxDistanceMeters'] as num).toDouble(),
      maxLocationAgeSeconds: (a['maxLocationAgeSeconds'] as num).toInt(),
      maxAccuracyMeters: (a['maxAccuracyMeters'] as num).toDouble(),
    );
  }
}

enum ArrivalCode { ok, locationMissing, locationStale, locationInaccurate, tooFar }

class ArrivalEval {
  const ArrivalEval(this.code, this.message, {this.distanceMeters, this.ageSeconds, this.accuracyMeters});

  final ArrivalCode code;
  final String message;
  final double? distanceMeters;
  final int? ageSeconds;
  final double? accuracyMeters;

  bool get allowed => code == ArrivalCode.ok;
  bool get needsRemeasure =>
      code == ArrivalCode.locationMissing || code == ArrivalCode.locationStale || code == ArrivalCode.locationInaccurate;
}

const double _earthRadius = 6371008.8; // 서버 Geo 와 같은 값

double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
  double rad(double d) => d * pi / 180;
  final p1 = rad(lat1), p2 = rad(lat2);
  final dp = p2 - p1, dl = rad(lng2 - lng1);
  final a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2);
  return 2 * _earthRadius * atan2(sqrt(a), sqrt(1 - a));
}

ArrivalEval evaluateArrival({
  required ArrivalPolicyValues policy,
  required double destLat,
  required double destLng,
  required DateTime now,
  double? lat,
  double? lng,
  double? accuracyMeters,
  DateTime? measuredAt,
}) {
  if (lat == null || lng == null || measuredAt == null) {
    return const ArrivalEval(ArrivalCode.locationMissing, '현재 위치가 확인되지 않습니다. 위치를 다시 측정하세요');
  }
  final distance = (haversineMeters(lat, lng, destLat, destLng) * 100).round() / 100;
  final age = max(0, now.difference(measuredAt).inSeconds);
  if (age > policy.maxLocationAgeSeconds) {
    return ArrivalEval(ArrivalCode.locationStale, '마지막 위치가 $age초 전입니다(허용 ${policy.maxLocationAgeSeconds}초). 위치를 다시 측정하세요',
        distanceMeters: distance, ageSeconds: age, accuracyMeters: accuracyMeters);
  }
  if (accuracyMeters == null || !(accuracyMeters <= policy.maxAccuracyMeters)) {
    final acc = accuracyMeters == null ? '확인 불가' : '${accuracyMeters.round()}m';
    return ArrivalEval(ArrivalCode.locationInaccurate,
        '위치 정확도가 $acc 입니다(허용 ${policy.maxAccuracyMeters.round()}m 이하). 하늘이 보이는 곳에서 다시 측정하세요',
        distanceMeters: distance, ageSeconds: age, accuracyMeters: accuracyMeters);
  }
  if (distance > policy.maxDistanceMeters) {
    return ArrivalEval(ArrivalCode.tooFar, '배송지에서 ${distance.round()}m — ${policy.maxDistanceMeters.round()}m 이내에서 도착 처리할 수 있습니다',
        distanceMeters: distance, ageSeconds: age, accuracyMeters: accuracyMeters);
  }
  return ArrivalEval(ArrivalCode.ok, '배송지에서 ${distance.round()}m — 도착 처리 가능',
      distanceMeters: distance, ageSeconds: age, accuracyMeters: accuracyMeters);
}

/// 서버 판정(arrival.check)을 앱 판정 형식으로 변환 (SIMULATED_ROUTE 배송은 서버 위치·판정을 따른다).
ArrivalEval? fromServerCheck(Map<String, dynamic>? c) {
  if (c == null) return null;
  final code = switch ('${c['code']}') {
    'OK' => ArrivalCode.ok,
    'LOCATION_STALE' => ArrivalCode.locationStale,
    'LOCATION_INACCURATE' => ArrivalCode.locationInaccurate,
    'TOO_FAR' => ArrivalCode.tooFar,
    _ => ArrivalCode.locationMissing,
  };
  return ArrivalEval(code, '${c['message'] ?? ''}',
      distanceMeters: (c['distanceMeters'] as num?)?.toDouble(),
      ageSeconds: (c['locationAgeSeconds'] as num?)?.toInt(),
      accuracyMeters: (c['accuracyMeters'] as num?)?.toDouble());
}

const arrivalExceptionReasons = <String, String>{
  'GPS_ERROR': 'GPS 오류',
  'ADDRESS_COORDINATE_ERROR': '주소 좌표 오류',
  'COMPLEX_ENTRY': '아파트·단지 진입 문제',
  'OTHER': '기타 (내용 입력)',
};
