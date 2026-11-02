import 'dart:async';

import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';

/// 위치 권한 확인 결과. 사용자 동의 없이 수집하지 않는다.
enum LocationPermissionState { unknown, serviceOff, denied, deniedForever, granted }

Future<LocationPermissionState> ensureLocationPermission({bool request = true}) async {
  if (!await Geolocator.isLocationServiceEnabled()) return LocationPermissionState.serviceOff;
  var p = await Geolocator.checkPermission();
  if (p == LocationPermission.denied && request) p = await Geolocator.requestPermission();
  return switch (p) {
    LocationPermission.always || LocationPermission.whileInUse => LocationPermissionState.granted,
    LocationPermission.deniedForever => LocationPermissionState.deniedForever,
    _ => LocationPermissionState.denied,
  };
}

class ReporterStatus {
  const ReporterStatus({this.running = false, this.lastSentAt, this.lastFix, this.lastError, this.pending = 0, this.sent = 0,
      this.accepted = 0, this.mocked = false, this.lastAccuracy});
  final bool running;
  final DateTime? lastSentAt;
  final DateTime? lastFix;
  final String? lastError;
  final int pending;
  final int sent;
  final int accepted;
  final bool mocked; // Android 모의 위치(모드 C) 감지 — 실 GPS 성공으로 합산하지 않음
  final double? lastAccuracy;
}

/// DEVICE_GPS 배송의 실제 위치 수집·전송.
/// 측정 → 버퍼 → 3초마다 배치 전송. 실패 시 버퍼 보존(최대 100) 후 재시도. 서버가 403/409 로 거부하면(배정 해제·완료) 중단.
/// 화면 꺼짐/백그라운드 지속은 Android 위치 포그라운드 서비스(알림 표시)로 시도하며 실기기 검증이 필요하다(D-24).
class LocationReporter {
  LocationReporter(this.api, this.deliveryId, {this.onRevoked});

  final ApiClient api;
  final String deliveryId;
  final VoidCallback? onRevoked;
  final status = ValueNotifier(const ReporterStatus());

  StreamSubscription<Position>? _sub;
  Timer? _flush;
  final List<Map<String, dynamic>> _buffer = [];
  int _sent = 0, _accepted = 0;
  bool _sending = false;
  int _lastSeq = 0;

  Future<String?> start() async {
    final perm = await ensureLocationPermission();
    if (perm != LocationPermissionState.granted) {
      final msg = switch (perm) {
        LocationPermissionState.serviceOff => '휴대폰 위치 서비스가 꺼져 있습니다',
        LocationPermissionState.deniedForever => '위치 권한이 영구 거부되었습니다. 설정에서 허용하세요',
        _ => '위치 권한이 필요합니다',
      };
      status.value = ReporterStatus(lastError: msg);
      return msg;
    }
    final settings = AndroidSettings(
      accuracy: LocationAccuracy.high,
      distanceFilter: 3,
      intervalDuration: const Duration(seconds: 2),
      foregroundNotificationConfig: const ForegroundNotificationConfig(
        notificationTitle: 'BARO-PICK 배송 중',
        notificationText: '배송 수행 중 위치를 고객에게 공유합니다',
        enableWakeLock: true,
      ),
    );
    _sub = Geolocator.getPositionStream(locationSettings: settings).listen(_onFix, onError: (Object e) {
      status.value = _snap(error: '위치 수신 오류: $e');
    });
    _flush = Timer.periodic(const Duration(seconds: 3), (_) => flush());
    status.value = _snap();
    return null;
  }

  Position? _lastPosition;

  /// 마지막으로 받은 단말 위치 (앱 도착 사전 판정용)
  Position? get lastPosition => _lastPosition;

  /// 즉시 재측정 후 서버로 전송. 정차 중에는 distanceFilter 때문에 새 위치가 오지 않아
  /// 고객 도착 판정(측정 후 30초 이내)을 통과하지 못하므로, 도착 버튼·재측정 버튼에서 호출한다.
  Future<String?> measureNow() async {
    try {
      final p = await Geolocator.getCurrentPosition(locationSettings: const LocationSettings(accuracy: LocationAccuracy.best))
          .timeout(const Duration(seconds: 15));
      _onFix(p);
      for (var i = 0; i < 20 && _sending; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 100));
      }
      await flush();
      return _buffer.isEmpty ? null : '위치 전송 대기 중 (네트워크 확인)';
    } catch (e) {
      return '위치 재측정 실패: $e';
    }
  }

  void _onFix(Position p) {
    _lastPosition = p;
    // seq: 측정시각(ms) 기반 단조 증가. 같은 시각 재수신은 서버가 DUPLICATE 로 처리
    var seq = p.timestamp.millisecondsSinceEpoch;
    if (seq <= _lastSeq) seq = _lastSeq + 1;
    _lastSeq = seq;
    _buffer.add({
      'seq': seq,
      'latitude': p.latitude,
      'longitude': p.longitude,
      'accuracyMeters': p.accuracy,
      'speedMps': p.speed.isFinite && p.speed >= 0 ? p.speed : null,
      'heading': p.heading.isFinite ? p.heading : null,
      'measuredAt': p.timestamp.toUtc().toIso8601String(),
    });
    if (_buffer.length > 100) _buffer.removeRange(0, _buffer.length - 100);
    status.value = _snap(fix: p.timestamp, mocked: p.isMocked, accuracy: p.accuracy);
  }

  Future<void> flush() async {
    if (_sending || _buffer.isEmpty) return;
    _sending = true;
    final batch = List<Map<String, dynamic>>.from(_buffer);
    try {
      final r = await api.post('/api/rider/deliveries/$deliveryId/locations', {'points': batch}) as Map<String, dynamic>;
      _buffer.removeRange(0, batch.length.clamp(0, _buffer.length));
      _sent += batch.length;
      _accepted += (r['accepted'] as num).toInt();
      status.value = _snap(sentAt: DateTime.now());
    } on ApiException catch (e) {
      if (e.status == 403 || e.status == 409) {
        // 배정 해제·완료·모드 불일치: 더 이상 보내지 않는다
        status.value = _snap(error: '서버가 위치 수신을 거부: $e');
        await stop();
        onRevoked?.call();
      } else {
        status.value = _snap(error: '전송 실패(재시도): $e');
      }
    } finally {
      _sending = false;
    }
  }

  ReporterStatus _snap({DateTime? fix, DateTime? sentAt, String? error, bool? mocked, double? accuracy}) {
    final s = status.value;
    return ReporterStatus(
      running: _sub != null,
      lastFix: fix ?? s.lastFix,
      lastSentAt: sentAt ?? s.lastSentAt,
      lastError: error ?? (sentAt != null ? null : s.lastError),
      pending: _buffer.length,
      sent: _sent,
      accepted: _accepted,
      mocked: mocked ?? s.mocked,
      lastAccuracy: accuracy ?? s.lastAccuracy,
    );
  }

  Future<void> stop() async {
    _flush?.cancel();
    _flush = null;
    await _sub?.cancel();
    _sub = null;
    status.value = _snap();
  }
}

/// 운행 중 대기 위치(배차 반경 판정용). 배송 위치 원장과 별도.
Future<String?> reportIdleLocation(ApiClient api) async {
  if (await ensureLocationPermission(request: false) != LocationPermissionState.granted) return '위치 권한 없음';
  try {
    final p = await Geolocator.getCurrentPosition(locationSettings: const LocationSettings(accuracy: LocationAccuracy.high))
        .timeout(const Duration(seconds: 15));
    await api.post('/api/rider/me/location', {
      'latitude': p.latitude,
      'longitude': p.longitude,
      'accuracyMeters': p.accuracy,
      'measuredAt': p.timestamp.toUtc().toIso8601String(),
    });
    return null;
  } catch (e) {
    return '대기 위치 보고 실패: $e';
  }
}
