import 'dart:async';
import 'dart:math';
import 'dart:typed_data';

import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';
import 'package:url_launcher/url_launcher.dart';

import 'arrival_check.dart';
import 'location_reporter.dart';
import 'navigation.dart';
import 'navigation_links.dart';
import 'scan_screen.dart';

/// 배송 수행: MFC 도착 → 포장 QR 인계(전량) → 배송 출발 → 고객 도착 → 사진 증빙 전달 완료.
/// 각 단계는 서버 판정 결과로만 진행되며, GPS 도착이나 시뮬레이터 경로 종료로 자동 진행하지 않는다.
class DeliveryScreen extends StatefulWidget {
  const DeliveryScreen({super.key, required this.session, required this.deliveryId});
  final Session session;
  final String deliveryId;

  @override
  State<DeliveryScreen> createState() => _DeliveryScreenState();
}

class _DeliveryScreenState extends State<DeliveryScreen> {
  /// 닫힌 화면(로그아웃·뒤로가기)에서 늦게 끝난 요청이 setState 를 호출해도 무시한다
  @override
  void setState(VoidCallback fn) {
    if (mounted) super.setState(fn);
  }

  Map<String, dynamic>? _d;
  String? _msg;
  bool _msgOk = false;
  bool _busy = false;
  LocationReporter? _reporter;
  RealtimeClient? _rt;
  Uint8List? _photo;
  String _method = 'NON_FACE_TO_FACE';
  String? _completeRequestId; // 완료 재시도 시 같은 requestId 사용 (중복 완료 방지)
  Timer? _poll;
  bool _revoked = false;

  // 앱 안 길안내 (서버가 준 카카오 경로 + 현재 위치 투영). 배송 상태와 무관한 보조 기능.
  bool _navOn = false;
  NavRoute? _nav;
  bool _navLoading = false;
  String? _navMsg;
  DateTime? _navRetryAt;
  final _reroute = RerouteMonitor();

  ApiClient get api => widget.session.api;
  static const _gpsStates = {'ASSIGNED', 'ARRIVED_AT_MFC', 'PICKED_UP', 'IN_TRANSIT', 'ARRIVED_AT_DESTINATION'};

  @override
  void initState() {
    super.initState();
    _load();
    _rt = RealtimeClient(
      baseUrl: widget.session.baseUrl,
      token: widget.session.token,
      topic: '/topic/riders/${widget.session.userId}',
      onConnected: _load,
      onMessage: (m) {
        final data = m['data'];
        if (data is Map && data['deliveryId'] == widget.deliveryId) {
          if (m['type'] == 'ASSIGNMENT_REVOKED') return _onRevoked();
          if (m['type'] == 'DELIVERY_UPDATED') _setDelivery(Map<String, dynamic>.from(data));
          _syncReporter();
        }
      },
    );
    _rt!.connect();
    _poll = Timer.periodic(const Duration(seconds: 15), (_) => _load());
  }

  @override
  void dispose() {
    _poll?.cancel();
    _reporter?.stop();
    _rt?.dispose();
    super.dispose();
  }

  void _onRevoked() {
    _reporter?.stop();
    _reporter = null;
    setState(() {
      _revoked = true;
      _msg = '이 배송의 배정이 해제되어 권한이 회수되었습니다';
    });
  }

  /// 상태가 바뀌면 이전 단계의 안내 메시지를 지운다 (예: 예외 승인으로 배송지 도착 전환).
  void _setDelivery(Map<String, dynamic> d) {
    setState(() {
      if (_d != null && _d!['status'] != d['status']) _msg = null;
      _d = d;
    });
    _navUpdate();
  }

  // ------------------------------------------------------------------ 앱 안 길안내

  /// 픽업 전에는 MFC, 픽업 후에는 배송지. 종료·취소면 길안내 없음.
  static String? _navTargetFor(String status) => switch (status) {
        'ASSIGNED' || 'ARRIVED_AT_MFC' => 'MFC',
        'PICKED_UP' || 'IN_TRANSIT' || 'ARRIVED_AT_DESTINATION' => 'DESTINATION',
        _ => null,
      };

  /// 현재 위치: 실 GPS 배송은 휴대폰 최신 측정값(없으면 서버 위치), SIMULATED 배송은 서버 위치(시뮬레이터).
  GeoPoint? _currentPosition() {
    final d = _d;
    if (d == null) return null;
    final p = d['locationMode'] == 'DEVICE_GPS' ? _reporter?.lastPosition : null;
    if (p != null) return GeoPoint(p.latitude, p.longitude);
    final pos = d['lastLocation'] as Map<String, dynamic>?;
    return pos == null ? null : GeoPoint((pos['latitude'] as num).toDouble(), (pos['longitude'] as num).toDouble());
  }

  Future<void> _fetchNav() async {
    final d = _d;
    final target = d == null ? null : _navTargetFor('${d['status']}');
    if (target == null) return _stopNav('배송이 종료되어 길안내를 마쳤습니다');
    if (_navLoading) return;
    final retry = _navRetryAt;
    if (retry != null && DateTime.now().isBefore(retry)) return;
    setState(() => _navLoading = true);
    try {
      final here = d!['locationMode'] == 'DEVICE_GPS' ? _currentPosition() : null; // SIMULATED 는 서버가 출발점을 정한다
      final q = here == null ? '' : '&lat=${here.lat}&lng=${here.lng}';
      final j = await api.get('/api/rider/deliveries/${widget.deliveryId}/navigation?target=$target$q') as Map<String, dynamic>;
      _reroute.reset();
      setState(() {
        _nav = NavRoute.fromJson(j);
        _navMsg = null;
        _navRetryAt = null;
      });
    } on ApiException catch (e) {
      final wait = (e.status == 429 || e.status == 503) ? 15 : 0; // 서버 재조회 제한과 같은 간격으로만 다시 시도
      setState(() {
        _navMsg = e.status == 0 ? e.userMessage : e.message; // 직선 경로로 대체하지 않는다
        _navRetryAt = wait == 0 ? null : DateTime.now().add(Duration(seconds: wait));
      });
    } finally {
      setState(() => _navLoading = false);
    }
  }

  void _stopNav([String? why]) {
    _reroute.reset();
    setState(() {
      _navOn = false;
      _nav = null;
      _navMsg = why;
    });
  }

  /// 위치·상태가 바뀔 때마다: 목적지가 바뀌었으면(픽업 완료 등) 새 경로, 2회 연속 경로 이탈이면 재탐색.
  void _navUpdate() {
    if (!_navOn || !mounted) return;
    final d = _d;
    if (d == null) return;
    final target = _navTargetFor('${d['status']}');
    if (target == null) return _stopNav('배송이 종료되어 길안내를 마쳤습니다');
    final nav = _nav;
    if (nav == null || nav.target != target) {
      _fetchNav();
      return;
    }
    final here = _currentPosition();
    if (here != null && _reroute.shouldReroute(nav.progress(here))) _fetchNav();
    setState(() {});
  }

  Future<void> _load() async {
    if (!mounted) return;
    try {
      final d = await api.get('/api/rider/deliveries/${widget.deliveryId}') as Map<String, dynamic>;
      _setDelivery(d);
      _syncReporter();
    } on ApiException catch (e) {
      if (e.status == 403) return _onRevoked();
      setState(() => _msg = e.toString());
    }
  }

  /// 실 GPS 배송만 단말 위치를 보낸다. SIMULATED_ROUTE 배송은 서버 시뮬레이터가 위치를 넣으므로 전송하지 않는다.
  void _syncReporter() {
    final d = _d;
    if (d == null || _revoked) return;
    final shouldRun = d['locationMode'] == 'DEVICE_GPS' && _gpsStates.contains(d['status']);
    if (shouldRun && _reporter == null) {
      _reporter = LocationReporter(api, widget.deliveryId, onRevoked: _load);
      _reporter!.status.addListener(_navUpdate); // 실 GPS 측정마다 길안내 진행 갱신
      _reporter!.start().then((err) {
        if (err != null) setState(() => _msg = err);
      });
      setState(() {});
    } else if (!shouldRun && _reporter != null) {
      _reporter!.stop();
      _reporter = null;
      setState(() {});
    }
  }

  Future<void> _act(Future<dynamic> Function() f, {String? ok}) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _msg = null;
    });
    try {
      final r = await f();
      if (r is Map<String, dynamic> && r.containsKey('deliveryId') && r.containsKey('status') && r.containsKey('packages')) {
        setState(() => _d = r);
      }
      setState(() => _msgOk = true);
      setState(() => _msgOk = true);
      if (ok != null) setState(() => _msg = ok);
    } on ApiException catch (e) {
      setState(() {
        _msgOk = false;
        _msg = '${e.code}: ${e.message}';
      });
      if (e.code == 'TIMEOUT') await _load(); // 결과 불명 → 원상태 재조회
    } finally {
      if (mounted) setState(() => _busy = false);
      await _load();
    }
  }

  Future<void> _scan() async {
    final code = await Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => const ScanScreen()));
    if (code == null) return;
    await _act(() async {
      final r = await api.post('/api/rider/deliveries/${widget.deliveryId}/handover-scans', {'qrCode': code}) as Map<String, dynamic>;
      setState(() => _msg = '${r['result']}: ${r['message']} (${r['handedOver']}/${r['total']})');
      return r;
    });
  }

  Future<void> _takePhoto() async {
    final x = await ImagePicker().pickImage(source: ImageSource.camera, maxWidth: 1600, imageQuality: 80);
    if (x == null) return;
    final bytes = await x.readAsBytes();
    setState(() => _photo = bytes);
  }

  Future<void> _complete() async {
    if (_photo == null) return;
    _completeRequestId ??= 'cmp-${widget.deliveryId}-${DateTime.now().millisecondsSinceEpoch}-${Random().nextInt(1 << 20)}';
    await _act(() async {
      final r = await api.postMultipart('/api/rider/deliveries/${widget.deliveryId}/complete',
          {'requestId': _completeRequestId!, 'method': _method}, fileField: 'photo', bytes: _photo, filename: 'proof.jpg');
      setState(() => _msg = '전달 완료: ${r['result']}');
      return r;
    });
  }

  Future<void> _navigate(double lat, double lng, String name) async {
    final app = kakaoMapAppRoute(lat, lng);
    if (await canLaunchUrl(app)) {
      await launchUrl(app);
      return;
    }
    final start = await _knownPosition();
    await launchUrl(kakaoMapWebRoute(destName: name, lat: lat, lng: lng, startLat: start?.lat, startLng: start?.lng),
        mode: LaunchMode.externalApplication);
  }

  /// 웹 길찾기 출발지. 실 GPS 배송은 기기의 마지막 위치, SIMULATED 배송(또는 기기 위치 없음)은 서버의 최신 위치.
  Future<({double lat, double lng})?> _knownPosition() async {
    final d = _d;
    if (d == null) return null;
    if (d['simulated'] != true) {
      try {
        final p = await Geolocator.getLastKnownPosition();
        if (p != null) return (lat: p.latitude, lng: p.longitude);
      } catch (_) {
        // 권한 없음 등 — 서버 위치로 대체
      }
    }
    final pos = d['lastLocation'] as Map<String, dynamic>?;
    if (pos == null) return null;
    return (lat: (pos['latitude'] as num).toDouble(), lng: (pos['longitude'] as num).toDouble());
  }

  @override
  Widget build(BuildContext context) {
    final d = _d;
    if (d == null) {
      return Scaffold(appBar: AppBar(title: const Text('배송')), body: Center(child: _msg == null ? const CircularProgressIndicator() : Text(_msg!)));
    }
    final status = '${d['status']}';
    final mfc = d['mfc'] as Map<String, dynamic>;
    final dest = d['destination'] as Map<String, dynamic>;
    final pos = d['lastLocation'] as Map<String, dynamic>?;
    final packages = (d['packages'] as List).cast<Map<String, dynamic>>();
    final simulated = d['simulated'] == true;
    final toCustomer = ['PICKED_UP', 'IN_TRANSIT', 'ARRIVED_AT_DESTINATION', 'DELIVERED'].contains(status);

    return Scaffold(
      appBar: AppBar(title: Text('${d['statusLabel']}'), actions: [
        if (simulated) const Padding(padding: EdgeInsets.only(right: 8), child: Chip(label: Text('SIMULATED'))),
      ]),
      body: Column(children: [
        SizedBox(
          height: _navOn ? 300 : 240,
          child: Stack(children: [
            Positioned.fill(
              child: DeliveryMap(
                destination: GeoPoint((dest['latitude'] as num).toDouble(), (dest['longitude'] as num).toDouble()),
                mfc: GeoPoint((mfc['latitude'] as num).toDouble(), (mfc['longitude'] as num).toDouble()),
                rider: pos == null ? null : GeoPoint((pos['latitude'] as num).toDouble(), (pos['longitude'] as num).toDouble()),
                riderLabel: '나',
                route: _navOn ? _nav?.points : null,
              ),
            ),
            if (_navOn) Positioned(left: 8, right: 8, top: 8, child: _navBanner()),
          ]),
        ),
        Expanded(
          child: ListView(padding: const EdgeInsets.all(12), children: [
            if (_revoked) const Card(color: Colors.red, child: Padding(padding: EdgeInsets.all(12),
                child: Text('배정 해제됨 — 이 배송을 더 이상 수행할 수 없습니다', style: TextStyle(color: Colors.white)))),
            Text('배송 ${d['deliveryId']} · 주문 ${d['orderId']}', style: const TextStyle(color: Colors.grey, fontSize: 12)),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.store),
              title: Text('픽업: ${mfc['name']}'),
              subtitle: Text('${mfc['mfcId']}${mfc['virtualSite'] == true ? ' · 테스트 거점' : ''}'),
              trailing: IconButton(icon: const Icon(Icons.navigation), tooltip: '카카오맵 길안내',
                  onPressed: () => _navigate((mfc['latitude'] as num).toDouble(), (mfc['longitude'] as num).toDouble(), '${mfc['name']}')),
            ),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.home),
              title: Text(toCustomer ? '전달: ${dest['address']} ${dest['addressDetail'] ?? ''}' : '전달지: 픽업 후 상세 공개'),
              subtitle: Text(toCustomer && d['customerNote'] != null ? '요청사항: ${d['customerNote']}' : ''),
              trailing: toCustomer
                  ? IconButton(icon: const Icon(Icons.navigation), tooltip: '카카오맵 길안내',
                      onPressed: () => _navigate((dest['latitude'] as num).toDouble(), (dest['longitude'] as num).toDouble(), '배송지'))
                  : null,
            ),
            _locationStatus(simulated),
            _navCard(status, mfc, dest, simulated),
            const Divider(),
            Text('포장 ${packages.where((p) => p['status'] == 'HANDED_OVER').length}/${d['packagesReady'] == true ? packages.length : d['expectedPackageCount']} 인계'
                '${d['packagesReady'] == true ? '' : ' (MFC 포장 준비 중)'}'),
            for (final p in packages)
              Text('· ${p['packageId']} — ${p['status']}${p['lastError'] != null ? ' (${p['lastError']})' : ''}'),
            const SizedBox(height: 12),
            ..._actions(status, d),
            if (_msg != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(_msg!, style: TextStyle(color: _msgOk ? Colors.green : Colors.red))),
          ]),
        ),
      ]),
    );
  }

  /// 지도 위 안내 배너: 다음 회전·남은 거리/시간. 경로가 없으면 사유.
  Widget _navBanner() {
    final nav = _nav;
    final here = _currentPosition();
    String title;
    String sub;
    if (nav == null || here == null) {
      title = _navLoading ? '경로를 찾는 중…' : (_navMsg ?? (here == null ? '현재 위치를 기다리는 중' : '경로 없음'));
      sub = '';
    } else {
      final p = nav.progress(here);
      title = p.instruction;
      sub = p.offRoute
          ? '경로를 벗어났습니다 (${p.offRouteMeters.round()}m) — ${_navLoading ? '재탐색 중' : '계속 벗어나면 재탐색'}'
          : p.summary;
      if (_navMsg != null) sub = '$sub\n$_navMsg';
    }
    return Material(
      color: Colors.indigo.shade700,
      borderRadius: BorderRadius.circular(10),
      elevation: 3,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
          Text(title, style: const TextStyle(color: Colors.white, fontSize: 20, fontWeight: FontWeight.bold)),
          if (sub.isNotEmpty) Text(sub, style: const TextStyle(color: Colors.white70, fontSize: 13)),
        ]),
      ),
    );
  }

  /// 길안내 버튼: 앱 안 경로 안내 + 카카오내비(이륜차, 음성 안내). 목적지는 상태에 따라 MFC 또는 배송지.
  Widget _navCard(String status, Map<String, dynamic> mfc, Map<String, dynamic> dest, bool simulated) {
    final target = _navTargetFor(status);
    if (target == null || _revoked) return const SizedBox.shrink();
    final toMfc = target == 'MFC';
    final p = toMfc ? mfc : dest;
    final name = toMfc ? '${mfc['name']}' : '배송지';
    final lat = (p['latitude'] as num).toDouble(), lng = (p['longitude'] as num).toDouble();
    return Card(
      margin: const EdgeInsets.only(top: 8),
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('길안내 → ${toMfc ? '픽업 MFC' : '배송지'}', style: const TextStyle(fontWeight: FontWeight.bold)),
          Wrap(spacing: 8, runSpacing: 4, children: [
            FilledButton.icon(
              icon: Icon(_navOn ? Icons.close : Icons.navigation),
              label: Text(_navOn ? '앱 안내 종료' : '앱 안에서 안내'),
              onPressed: () {
                if (_navOn) return _stopNav();
                setState(() {
                  _navOn = true;
                  _navMsg = null;
                  _navRetryAt = null;
                });
                _fetchNav();
              },
            ),
            OutlinedButton.icon(
              icon: const Icon(Icons.two_wheeler),
              label: const Text('카카오내비'),
              onPressed: () async {
                final m = await launchKakaoNavi(name: name, lat: lat, lng: lng);
                if (m != null) setState(() => _navMsg = m);
              },
            ),
          ]),
          Text(
            _nav?.note ?? '앱 안내: 카카오 자동차 경로 기준 · 카카오내비: 이륜차 경로·음성 안내(카카오내비 앱 필요)',
            style: const TextStyle(color: Colors.grey, fontSize: 11),
          ),
          if (simulated)
            const Text('SIMULATED 배송: 앱 안내는 시뮬레이터 위치 기준, 카카오내비는 휴대폰 실제 위치 기준입니다',
                style: TextStyle(color: Colors.purple, fontSize: 11)),
          if (!_navOn && _navMsg != null) Text(_navMsg!, style: const TextStyle(color: Colors.orange, fontSize: 12)),
        ]),
      ),
    );
  }

  Widget _locationStatus(bool simulated) {
    if (simulated) {
      return const Text('위치: 이 배송은 테스트 서버의 SIMULATED_ROUTE 재생기가 위치를 넣습니다 (휴대폰 GPS 미전송)',
          style: TextStyle(color: Colors.purple));
    }
    final r = _reporter;
    if (r == null) return const Text('위치 전송: 대기', style: TextStyle(color: Colors.grey));
    return ValueListenableBuilder<ReporterStatus>(
      valueListenable: r.status,
      builder: (_, s, _) => Text(
        '실GPS 전송 ${s.running ? 'ON' : 'OFF'} · 보냄 ${s.sent} / 반영 ${s.accepted} · 대기 ${s.pending}'
        '${s.lastAccuracy == null ? '' : ' · 정확도 ${s.lastAccuracy!.toStringAsFixed(0)}m'}'
        '${s.lastSentAt == null ? '' : ' · ${hhmm(s.lastSentAt!.toUtc().toIso8601String())}'}'
        '${s.mocked ? '\n⚠ 모의 위치(Mock location) 감지 — 실 GPS 검증 결과로 보지 않음' : ''}'
        '${s.lastError == null ? '' : '\n${s.lastError}'}',
        style: TextStyle(color: s.lastError != null || s.mocked ? Colors.orange : Colors.green),
      ),
    );
  }

  List<Widget> _actions(String status, Map<String, dynamic> d) {
    if (_revoked) return const [];
    final id = widget.deliveryId;
    final b = _busy;
    switch (status) {
      case 'ASSIGNED':
        return [
          FilledButton(onPressed: b ? null : () => _act(() => api.post('/api/rider/deliveries/$id/arrive-mfc')), child: const Text('MFC 도착')),
          TextButton(onPressed: b ? null : _release, child: const Text('배차 취소 (픽업 전)')),
        ];
      case 'ARRIVED_AT_MFC':
        return [
          FilledButton.icon(onPressed: b || d['packagesReady'] != true ? null : _scan, icon: const Icon(Icons.qr_code_scanner),
              label: Text(d['packagesReady'] == true ? '포장 QR 스캔' : 'MFC 포장 준비 대기 중')),
          const Text('모든 포장을 스캔해야 픽업 완료됩니다', style: TextStyle(color: Colors.grey, fontSize: 12)),
          TextButton(onPressed: b ? null : _release, child: const Text('배차 취소 (픽업 전)')),
        ];
      case 'PICKED_UP':
        return [FilledButton(onPressed: b ? null : () => _act(() => api.post('/api/rider/deliveries/$id/start')), child: const Text('배송 출발'))];
      case 'IN_TRANSIT':
        final eval = _arrivalEval(d);
        final policy = ArrivalPolicyValues.fromJson(d['arrival'] as Map<String, dynamic>?);
        final ex = (d['arrival'] as Map<String, dynamic>?)?['exception'] as Map<String, dynamic>?;
        final pending = ex != null && ex['status'] == 'PENDING';
        final simulated = d['simulated'] == true;
        return [
          if (policy != null)
            Text('도착 가능 범위: 배송지 ${policy.maxDistanceMeters.round()}m 이내 · 위치 ${policy.maxLocationAgeSeconds}초 이내 · 정확도 ${policy.maxAccuracyMeters.round()}m 이하',
                style: const TextStyle(color: Colors.grey, fontSize: 12)),
          if (eval != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Text(eval.message, style: TextStyle(color: eval.allowed ? Colors.green.shade700 : Colors.orange.shade800)),
            ),
          // 실 GPS: 버튼을 누르면 즉시 재측정·전송 후 앱 판정 → 서버 최종 판정. 시뮬레이션: 서버 판정이 허용일 때만 활성
          FilledButton(
              onPressed: b || pending || (simulated && eval?.allowed != true) ? null : _arrive, child: const Text('고객 도착')),
          const Text('도착 버튼은 직접 눌러야 합니다 (GPS 도착만으로 자동 처리하지 않음)', style: TextStyle(color: Colors.grey, fontSize: 12)),
          if (eval != null && !eval.allowed) ...[
            OutlinedButton.icon(onPressed: b ? null : _remeasure, icon: const Icon(Icons.my_location), label: const Text('위치 다시 측정')),
            if (!pending)
              TextButton.icon(onPressed: b ? null : _requestException, icon: const Icon(Icons.report_problem_outlined),
                  label: const Text('도착 예외 요청 (관리자 승인)')),
          ],
          if (ex != null) _exceptionStatus(ex),
        ];
      case 'ARRIVED_AT_DESTINATION':
        return [
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'NON_FACE_TO_FACE', label: Text('비대면')),
              ButtonSegment(value: 'FACE_TO_FACE', label: Text('대면')),
            ],
            selected: {_method},
            onSelectionChanged: (s) => setState(() => _method = s.first),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(onPressed: b ? null : _takePhoto, icon: const Icon(Icons.photo_camera),
              label: Text(_photo == null ? '전달 사진 촬영 (필수)' : '다시 촬영')),
          if (_photo != null) Padding(padding: const EdgeInsets.all(8), child: Image.memory(_photo!, height: 140)),
          const Text('다른 고객 정보·출입 비밀번호가 찍히지 않게 촬영하세요', style: TextStyle(color: Colors.grey, fontSize: 12)),
          FilledButton(onPressed: b || _photo == null ? null : _complete, child: const Text('전달 완료')),
        ];
      case 'DELIVERED':
        return [const Text('배송 완료 — 위치 공유 종료', style: TextStyle(color: Colors.green, fontSize: 18))];
      default:
        return [Text('현재 상태: $status')];
    }
  }

  Future<void> _release() => _act(() => api.post('/api/rider/deliveries/${widget.deliveryId}/release', {'reason': '라이더 배차취소'}),
      ok: '배차를 취소했습니다');

  // ------------------------------------------------------------------ 고객 도착 (DECISIONS.md A-01~A-04)

  /// 앱 사전 판정. 실 GPS 는 단말 마지막 위치로 직접 판정, SIMULATED_ROUTE 는 서버 위치·판정을 따른다.
  ArrivalEval? _arrivalEval(Map<String, dynamic> d) {
    final a = d['arrival'] as Map<String, dynamic>?;
    final policy = ArrivalPolicyValues.fromJson(a);
    if (a == null || policy == null) return null;
    if (d['simulated'] == true) return fromServerCheck(a['check'] as Map<String, dynamic>?);
    final dest = d['destination'] as Map<String, dynamic>;
    final p = _reporter?.lastPosition;
    return evaluateArrival(
      policy: policy,
      destLat: (dest['latitude'] as num).toDouble(),
      destLng: (dest['longitude'] as num).toDouble(),
      now: DateTime.now(),
      lat: p?.latitude,
      lng: p?.longitude,
      accuracyMeters: p?.accuracy,
      measuredAt: p?.timestamp,
    );
  }

  /// 재측정·전송. 성공 null, 실패 시 사유.
  Future<String?> _measure() async {
    final r = _reporter;
    if (r == null) return '위치 수집이 시작되지 않았습니다 (위치 권한 확인)';
    return r.measureNow();
  }

  Future<void> _arrive() async {
    final d = _d;
    if (d == null) return;
    if (d['simulated'] != true) {
      // 정차 중에는 새 위치가 오지 않으므로 누를 때 재측정·전송
      setState(() => _busy = true);
      final err = await _measure();
      setState(() => _busy = false);
      final eval = _arrivalEval(d);
      if (err != null || eval == null || !eval.allowed) {
        setState(() {
          _msgOk = false;
          _msg = err ?? eval?.message ?? '도착 판정 정보를 불러오지 못했습니다';
        });
        return;
      }
    }
    await _act(() => api.post('/api/rider/deliveries/${widget.deliveryId}/arrive-destination'), ok: '고객 도착 처리 완료');
  }

  Future<void> _remeasure() async {
    if (_d?['simulated'] == true) {
      await _load(); // 시뮬레이션: 서버 위치로 다시 판정
      return;
    }
    setState(() => _busy = true);
    final err = await _measure();
    setState(() {
      _busy = false;
      _msgOk = err == null;
      _msg = err ?? '위치를 다시 측정했습니다';
    });
    await _load();
  }

  Future<void> _requestException() async {
    String reason = 'GPS_ERROR';
    final note = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setD) => AlertDialog(
          title: const Text('도착 예외 요청'),
          content: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('정상 판정이 불가능한 사유를 선택하세요. 관리자 승인 후 도착 처리됩니다.', style: TextStyle(fontSize: 13)),
              RadioGroup<String>(
                groupValue: reason,
                onChanged: (v) => setD(() => reason = v ?? reason),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  for (final e in arrivalExceptionReasons.entries)
                    RadioListTile<String>(dense: true, contentPadding: EdgeInsets.zero, value: e.key, title: Text(e.value)),
                ]),
              ),
              TextField(controller: note, maxLength: 300, decoration: const InputDecoration(labelText: '상세 내용 (기타 사유는 필수)')),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('취소')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('요청')),
          ],
        ),
      ),
    );
    if (ok != true) return;
    await _act(() => api.post('/api/rider/deliveries/${widget.deliveryId}/arrival-exceptions',
        {'reasonCode': reason, 'note': note.text.trim().isEmpty ? null : note.text.trim()}), ok: '도착 예외를 요청했습니다. 관리자 승인을 기다리세요');
    await _load();
  }

  Widget _exceptionStatus(Map<String, dynamic> ex) {
    final reason = arrivalExceptionReasons[ex['reasonCode']] ?? '${ex['reasonCode']}';
    final (text, color) = switch ('${ex['status']}') {
      'PENDING' => ('도착 예외 요청 중 ($reason) — 관리자 승인 대기', Colors.orange.shade800),
      'APPROVED' => ('도착 예외 승인됨: ${ex['decisionNote'] ?? ''}', Colors.green.shade700),
      'REJECTED' => ('도착 예외 반려: ${ex['decisionNote'] ?? ''}', Colors.red.shade700),
      _ => ('도착 예외 요청 무효 처리됨', Colors.grey),
    };
    return Padding(padding: const EdgeInsets.only(top: 6), child: Text(text, style: TextStyle(color: color)));
  }
}
