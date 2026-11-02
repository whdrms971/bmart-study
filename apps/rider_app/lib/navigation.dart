import 'dart:math' as math;

import 'package:delivery_common/delivery_common.dart';

/// 앱 안 길안내 계산. 경로·안내 지점은 서버(카카오 자동차 길찾기)가 주고, 앱은 현재 위치를 경로에 투영해
/// 다음 안내·남은 거리·경로 이탈만 계산한다. 길안내 결과로 배송 상태를 바꾸지 않는다.

/// 경로선에서 이만큼 벗어나면 이탈로 본다 (2회 연속이면 재탐색). [설계 제안]
const double offRouteThresholdMeters = 50;

/// 남은 거리가 이보다 짧으면 "목적지 근처".
const double nearTargetMeters = 30;

const _earthRadius = 6371008.8;

double metersBetween(GeoPoint a, GeoPoint b) {
  double rad(double d) => d * math.pi / 180;
  final dp = rad(b.lat - a.lat), dl = rad(b.lng - a.lng);
  final h = math.sin(dp / 2) * math.sin(dp / 2) + math.cos(rad(a.lat)) * math.cos(rad(b.lat)) * math.sin(dl / 2) * math.sin(dl / 2);
  return 2 * _earthRadius * math.atan2(math.sqrt(h), math.sqrt(1 - h));
}

class NavGuide {
  NavGuide(this.point, this.type, this.guidance, this.name);
  final GeoPoint point;

  /// 카카오 안내 유형 (100 출발지, 101 목적지 등)
  final int type;
  final String guidance;
  final String name;

  /// 경로 시작부터 이 안내 지점까지의 경로상 거리(m) — 경로에 투영해 계산
  double along = 0;
}

class NavRoute {
  NavRoute._(this.target, this.points, this.guides, this.distanceMeters, this.durationSeconds, this.computedAt, this.note, this.cached) {
    _cumulative = [0];
    for (var i = 1; i < points.length; i++) {
      _cumulative.add(_cumulative.last + metersBetween(points[i - 1], points[i]));
    }
    for (final g in guides) {
      g.along = _project(g.point).along;
    }
  }

  factory NavRoute.fromJson(Map<String, dynamic> j) {
    final pts = [
      for (final p in (j['points'] as List)) GeoPoint(((p as List)[0] as num).toDouble(), (p[1] as num).toDouble()),
    ];
    final guides = [
      for (final g in (j['guides'] as List? ?? const []))
        NavGuide(GeoPoint((g['lat'] as num).toDouble(), (g['lng'] as num).toDouble()), (g['type'] as num?)?.toInt() ?? -1,
            '${g['guidance'] ?? ''}', '${g['name'] ?? ''}'),
    ];
    return NavRoute._('${j['target']}', pts, guides, (j['distanceMeters'] as num).toInt(), (j['durationSeconds'] as num).toInt(),
        DateTime.tryParse('${j['computedAt']}'), '${j['note'] ?? ''}', j['cached'] == true);
  }

  final String target;
  final List<GeoPoint> points;
  final List<NavGuide> guides;
  final int distanceMeters;
  final int durationSeconds;
  final DateTime? computedAt;
  final String note;
  final bool cached;
  late final List<double> _cumulative;

  double get length => _cumulative.last;

  /// 점 [p] 에서 경로선까지의 최단 거리와, 그 투영점의 경로상 거리.
  ({double dist, double along}) _project(GeoPoint p) {
    if (points.length < 2) return (dist: points.isEmpty ? double.infinity : metersBetween(p, points.first), along: 0);
    // p 를 원점으로 하는 국지 평면(등장방형) — 수 km 범위에서 충분히 정확
    final k = math.pi / 180 * _earthRadius;
    final cosLat = math.cos(p.lat * math.pi / 180);
    double x(GeoPoint q) => (q.lng - p.lng) * k * cosLat;
    double y(GeoPoint q) => (q.lat - p.lat) * k;
    var best = (dist: double.infinity, along: 0.0);
    for (var i = 0; i + 1 < points.length; i++) {
      final ax = x(points[i]), ay = y(points[i]), bx = x(points[i + 1]), by = y(points[i + 1]);
      final dx = bx - ax, dy = by - ay;
      final len2 = dx * dx + dy * dy;
      final t = len2 == 0 ? 0.0 : (-(ax * dx + ay * dy) / len2).clamp(0.0, 1.0);
      final cx = ax + t * dx, cy = ay + t * dy;
      final dist = math.sqrt(cx * cx + cy * cy);
      if (dist < best.dist) {
        best = (dist: dist, along: _cumulative[i] + t * (_cumulative[i + 1] - _cumulative[i]));
      }
    }
    return best;
  }

  NavProgress progress(GeoPoint here) {
    final pr = _project(here);
    final remaining = math.max(0.0, length - pr.along);
    final seconds = length <= 0 ? 0 : (durationSeconds * remaining / length).round();
    NavGuide? next;
    for (final g in guides) {
      if (g.type == 100) continue; // 출발지
      if (g.along > pr.along + 10) {
        next = g;
        break;
      }
    }
    return NavProgress(pr.dist, pr.along, remaining, seconds, next, next == null ? null : next.along - pr.along);
  }
}

class NavProgress {
  NavProgress(this.offRouteMeters, this.alongMeters, this.remainingMeters, this.remainingSeconds, this.next, this.toNextMeters);
  final double offRouteMeters;
  final double alongMeters;
  final double remainingMeters;
  final int remainingSeconds;
  final NavGuide? next;
  final double? toNextMeters;

  bool get offRoute => offRouteMeters > offRouteThresholdMeters;
  bool get nearTarget => remainingMeters < nearTargetMeters;

  /// 큰 글씨 안내: "300m 앞 좌회전 · 해운대로"
  String get instruction {
    final g = next;
    if (nearTarget || g == null) return '목적지 근처입니다';
    final d = formatDistance(toNextMeters!);
    if (g.type == 101) return '$d 앞 목적지';
    final what = g.guidance.isEmpty ? '안내 지점' : g.guidance;
    return g.name.isEmpty ? '$d 앞 $what' : '$d 앞 $what · ${g.name}';
  }

  /// 보조 문구: "남은 2.1km · 약 8분"
  String get summary => '남은 ${formatDistance(remainingMeters)} · 약 ${(remainingSeconds / 60).ceil()}분';
}

String formatDistance(double m) {
  if (m < 1000) return '${(m / 10).round() * 10}m';
  return '${(m / 1000).toStringAsFixed(1)}km';
}

/// 경로 이탈이 2회 연속이면 재탐색. 위치 한 번 튀는 것으로 유료 길찾기를 다시 부르지 않게 한다.
class RerouteMonitor {
  int _off = 0;

  bool shouldReroute(NavProgress p) {
    _off = p.offRoute ? _off + 1 : 0;
    return _off >= 2;
  }

  void reset() => _off = 0;
}
