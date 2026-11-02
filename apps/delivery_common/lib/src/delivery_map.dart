import 'package:flutter/material.dart';
import 'package:kakao_map_sdk/kakao_map_sdk.dart';

import 'config.dart';

class GeoPoint {
  const GeoPoint(this.lat, this.lng);
  final double lat;
  final double lng;
}

/// 배송 지도 (카카오 지도 Android SDK v2, 서드파티 Flutter 브릿지 kakao_map_sdk).
/// 카메라 정책(6.1): 최초 1회 라이더+배송지 맞춤 → 이후 [follow] 가 true 일 때만 라이더 따라가기.
/// 사용자가 직접 팬/줌하면 [onUserGesture] 로 알려 자동 추적을 끈다. [focusRequest] 값이 바뀌면 라이더 위치로 이동.
class DeliveryMap extends StatefulWidget {
  const DeliveryMap({
    super.key,
    required this.destination,
    this.rider,
    this.mfc,
    this.route,
    this.follow = true,
    this.onUserGesture,
    this.focusRequest = 0,
    this.riderLabel = '라이더',
  });

  final GeoPoint destination;
  final GeoPoint? rider;
  final GeoPoint? mfc;
  final List<GeoPoint>? route;
  final bool follow;
  final VoidCallback? onUserGesture;
  final int focusRequest;
  final String riderLabel;

  @override
  State<DeliveryMap> createState() => _DeliveryMapState();
}

class _DeliveryMapState extends State<DeliveryMap> {
  KakaoMapController? _c;
  Poi? _riderPoi;
  PoiStyle? _riderStyle;
  bool _initialFitDone = false;
  String? _error;

  LatLng _ll(GeoPoint p) => LatLng(p.lat, p.lng);

  Future<PoiStyle> _dotStyle(Color color, double size) async {
    final img = await KImage.fromWidget(
      Container(
        width: size,
        height: size,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle, border: Border.all(color: Colors.white, width: 3)),
      ),
      Size(size, size),
      context: context,
    );
    return PoiStyle(icon: img, anchor: const KPoint(0.5, 0.5));
  }

  Future<void> _onReady(KakaoMapController c) async {
    _c = c;
    try {
      final destStyle = await _dotStyle(Colors.red, 26);
      await c.labelLayer.addPoi(_ll(widget.destination), style: destStyle, text: '배송지');
      if (widget.mfc != null) {
        await c.labelLayer.addPoi(_ll(widget.mfc!), style: await _dotStyle(Colors.orange, 22), text: 'MFC');
      }
      if (widget.route != null && widget.route!.length >= 2) {
        await c.routeLayer.addRoute(widget.route!.map(_ll).toList(), RouteStyle(Colors.blue, 8));
      }
      _riderStyle = await _dotStyle(Colors.deepPurple, 30);
      await _syncRider();
      await _initialFit();
    } catch (e) {
      setState(() => _error = '지도 오버레이 오류: $e');
    }
  }

  /// 최초 1회: 라이더+배송지 범위 (라이더 없으면 배송지 중심)
  Future<void> _initialFit() async {
    if (_c == null || _initialFitDone) return;
    final pts = [_ll(widget.destination), if (widget.rider != null) _ll(widget.rider!)];
    if (pts.length == 1) {
      await _c!.moveCamera(CameraUpdate.newCenterPosition(pts.first, zoomLevel: 16));
    } else {
      await _c!.moveCamera(CameraUpdate.fitMapPoints(pts, padding: 80));
    }
    _initialFitDone = true;
  }

  /// 라이더 마커 갱신을 직렬화한다. 지도 준비(_onReady)와 위치 갱신(didUpdateWidget)이 겹치면
  /// 둘 다 _riderPoi == null 을 보고 addPoi 를 호출해 움직이지 않는 중복 마커가 남던 문제 방지.
  Future<void> _riderOp = Future.value();

  Future<void> _syncRider() {
    final next = _riderOp.then((_) => _syncRiderNow());
    _riderOp = next.catchError((_) {});
    return next;
  }

  Future<void> _syncRiderNow() async {
    final c = _c;
    if (c == null || _riderStyle == null) return;
    final r = widget.rider;
    if (r == null) {
      await _riderPoi?.remove();
      _riderPoi = null;
      return;
    }
    if (_riderPoi == null) {
      _riderPoi = await c.labelLayer.addPoi(_ll(r), style: _riderStyle!, text: widget.riderLabel);
    } else {
      await _riderPoi!.move(_ll(r), 800);
    }
  }

  @override
  void didUpdateWidget(covariant DeliveryMap old) {
    super.didUpdateWidget(old);
    final moved = old.rider?.lat != widget.rider?.lat || old.rider?.lng != widget.rider?.lng;
    if (moved) {
      _syncRider().then((_) async {
        if (!_initialFitDone) {
          await _initialFit();
        } else if (widget.follow && widget.rider != null) {
          await _c?.moveCamera(CameraUpdate.newCenterPosition(_ll(widget.rider!)), animation: const CameraAnimation(600));
        }
      });
    }
    if (old.focusRequest != widget.focusRequest && widget.rider != null) {
      _c?.moveCamera(CameraUpdate.newCenterPosition(_ll(widget.rider!), zoomLevel: 16), animation: const CameraAnimation(500));
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!AppConfig.hasKakaoKey) {
      return _Placeholder(
        message: 'KAKAO_NATIVE_APP_KEY 미설정 — 지도 없이 좌표만 표시합니다',
        rider: widget.rider,
        destination: widget.destination,
      );
    }
    return Stack(children: [
      KakaoMap(
        option: KakaoMapOption(position: _ll(widget.rider ?? widget.destination), zoomLevel: 15),
        onMapReady: _onReady,
        onMapError: (e) => setState(() => _error = '지도 오류(키 해시/네이티브 키 확인): $e'),
        onCameraMoveStart: (g) {
          if (g != GestureType.unknown) widget.onUserGesture?.call();
        },
      ),
      if (_error != null)
        Positioned(left: 8, right: 8, top: 8, child: Material(color: Colors.red.shade50, child: Padding(
          padding: const EdgeInsets.all(8), child: Text(_error!, style: const TextStyle(color: Colors.red))))),
    ]);
  }
}

class _Placeholder extends StatelessWidget {
  const _Placeholder({required this.message, required this.destination, this.rider});
  final String message;
  final GeoPoint destination;
  final GeoPoint? rider;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.grey.shade200,
      alignment: Alignment.center,
      padding: const EdgeInsets.all(16),
      child: Text(
        '$message\n배송지 ${destination.lat.toStringAsFixed(6)}, ${destination.lng.toStringAsFixed(6)}\n'
        '라이더 ${rider == null ? '—' : '${rider!.lat.toStringAsFixed(6)}, ${rider!.lng.toStringAsFixed(6)}'}',
        textAlign: TextAlign.center,
      ),
    );
  }
}
