import 'dart:async';

import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';

import 'delivery_screen.dart';
import 'location_reporter.dart';

/// 운행 상태 · 배차 제안 · 수행 중 배송. 제안은 STOMP 푸시 + 3초 폴링(백업)으로 받고, 수락은 서버가 최종 판정한다.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.session, required this.onLogout});
  final Session session;
  final VoidCallback onLogout;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  /// 닫힌 화면(로그아웃·뒤로가기)에서 늦게 끝난 요청이 setState 를 호출해도 무시한다
  @override
  void setState(VoidCallback fn) {
    if (mounted) super.setState(fn);
  }

  Map<String, dynamic>? _me;
  List<dynamic> _offers = [];
  String? _msg;
  bool _busy = false;
  RealtimeClient? _rt;
  RealtimeState _rtState = RealtimeState.disconnected;
  StreamSubscription<RealtimeState>? _rtSub;
  Timer? _poll;
  Timer? _idle;
  Timer? _tick;
  String? _idleError;

  ApiClient get api => widget.session.api;

  @override
  void initState() {
    super.initState();
    _refresh();
    _rt = RealtimeClient(
      baseUrl: widget.session.baseUrl,
      token: widget.session.token,
      topic: '/topic/riders/${widget.session.userId}',
      onConnected: _refresh,
      onMessage: (m) {
        final t = '${m['type']}';
        if (t.startsWith('OFFER_') || t == 'DELIVERY_UPDATED') _refresh();
        if (t == 'ASSIGNMENT_REVOKED') {
          setState(() => _msg = '배정이 해제되었습니다: ${(m['data'] as Map?)?['deliveryId']}');
          _refresh();
        }
      },
    );
    _rtSub = _rt!.states.listen((s) => setState(() => _rtState = s));
    _rt!.connect();
    _poll = Timer.periodic(const Duration(seconds: 3), (_) => _refreshOffers());
    _tick = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _poll?.cancel();
    _idle?.cancel();
    _tick?.cancel();
    _rtSub?.cancel();
    _rt?.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final me = await api.get('/api/rider/me') as Map<String, dynamic>;
      setState(() => _me = me);
      _syncIdleReporting();
      await _refreshOffers();
    } on ApiException catch (e) {
      if (e.isAuth) return widget.onLogout();
      setState(() => _msg = e.toString());
    }
  }

  Future<void> _refreshOffers() async {
    try {
      final o = await api.get('/api/rider/offers') as List<dynamic>;
      if (mounted) setState(() => _offers = o);
    } on ApiException catch (_) {/* 다음 폴링에서 재시도. 401 은 ApiClient.onUnauthorized 가 세션 로그아웃으로 처리 */}
  }

  /// 운행 중에는 30초마다 대기 위치를 보고(근거리 MFC 배차 반경 판정용)
  void _syncIdleReporting() {
    final onDuty = _me?['dutyStatus'] != 'OFF_DUTY';
    if (onDuty && _idle == null) {
      reportIdleLocation(api).then((e) => setState(() => _idleError = e));
      _idle = Timer.periodic(const Duration(seconds: 30), (_) async {
        final e = await reportIdleLocation(api);
        if (mounted) setState(() => _idleError = e);
      });
    } else if (!onDuty) {
      _idle?.cancel();
      _idle = null;
    }
  }

  Future<void> _setDuty(String s) async {
    if (s != 'OFF_DUTY') {
      final p = await ensureLocationPermission();
      if (p != LocationPermissionState.granted) {
        setState(() => _msg = '운행 시작에는 위치 권한이 필요합니다 (원거리 SIMULATED 테스트는 권한 없이도 배차 가능)');
      }
    }
    await _run(() => api.post('/api/rider/me/duty', {'dutyStatus': s}));
  }

  Future<void> _run(Future<dynamic> Function() f, {String? ok}) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _msg = null;
    });
    try {
      await f();
      if (ok != null) setState(() => _msg = ok);
    } on ApiException catch (e) {
      final hint = switch (e.code) {
        'OFFER_EXPIRED' => '시간이 만료된 배차입니다',
        'ALREADY_ASSIGNED' => '이미 다른 라이더에게 배정되었습니다',
        'DELIVERY_CANCELLED' => '주문이 취소되었습니다',
        'CAPACITY_EXCEEDED' => '동시 수행 가능 건수를 초과했습니다',
        _ => e.message,
      };
      setState(() => _msg = '${e.code}: $hint');
    } finally {
      if (mounted) setState(() => _busy = false);
      await _refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    final duty = '${_me?['dutyStatus'] ?? 'OFF_DUTY'}';
    final active = (_me?['activeDeliveries'] as List?) ?? const [];
    return Scaffold(
      appBar: AppBar(title: Text('라이더 · ${widget.session.userId}'), actions: [
        Padding(
          padding: const EdgeInsets.only(right: 4),
          child: Icon(Icons.circle, size: 12, color: _rtState == RealtimeState.connected ? Colors.green : Colors.orange),
        ),
        IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh)),
        IconButton(onPressed: widget.onLogout, icon: const Icon(Icons.logout)),
      ]),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(padding: const EdgeInsets.all(12), children: [
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'OFF_DUTY', label: Text('운행 종료')),
              ButtonSegment(value: 'ON_DUTY_ACCEPTING', label: Text('운행·신규배차')),
              ButtonSegment(value: 'ON_DUTY_PAUSED', label: Text('신규배차 중지')),
            ],
            selected: {duty},
            onSelectionChanged: _busy ? null : (s) => _setDuty(s.first),
          ),
          if (_idleError != null && duty != 'OFF_DUTY') Text(_idleError!, style: const TextStyle(color: Colors.orange, fontSize: 12)),
          if (_msg != null) Padding(padding: const EdgeInsets.symmetric(vertical: 8), child: Text(_msg!, style: const TextStyle(color: Colors.red))),
          const SizedBox(height: 8),
          Text('배차 제안 (${_offers.length})', style: Theme.of(context).textTheme.titleMedium),
          if (_offers.isEmpty) const Padding(padding: EdgeInsets.all(8), child: Text('대기 중인 제안이 없습니다', style: TextStyle(color: Colors.grey))),
          for (final o in _offers) _offerCard(o as Map<String, dynamic>),
          const Divider(),
          Text('수행 중 배송 (${active.length})', style: Theme.of(context).textTheme.titleMedium),
          for (final d in active)
            Card(
              child: ListTile(
                title: Text('${d['statusLabel']} · ${d['deliveryId']}'),
                subtitle: Text('${d['mfc']['name']}\n→ ${d['destination']['address']} ${d['destination']['addressDetail'] ?? ''}'),
                isThreeLine: true,
                trailing: d['simulated'] == true ? const Chip(label: Text('SIMULATED')) : const Chip(label: Text('GPS')),
                onTap: () => Navigator.of(context)
                    .push(MaterialPageRoute(builder: (_) => DeliveryScreen(session: widget.session, deliveryId: d['deliveryId'] as String)))
                    .then((_) => _refresh()),
              ),
            ),
        ]),
      ),
    );
  }

  Widget _offerCard(Map<String, dynamic> o) {
    final expires = DateTime.tryParse('${o['expiresAt']}');
    final left = expires == null ? 0 : expires.difference(DateTime.now()).inSeconds.clamp(0, 9999);
    return Card(
      color: Colors.amber.shade50,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Text('새 배차 · 남은 $left초', style: const TextStyle(fontWeight: FontWeight.bold)),
            const Spacer(),
            if (o['locationMode'] == 'SIMULATED_ROUTE') const Chip(label: Text('SIMULATED')),
          ]),
          Text('픽업: ${o['mfc']['name']} (${o['mfc']['mfcId']})'),
          Text('전달: ${o['destinationAddress']}'),
          Text('직선거리 ${meters(o['straightDistanceMeters'] as num?)} · 포장 ${o['expectedPackageCount']}개'
              '${o['totalWeightGrams'] == null ? '' : ' · ${o['totalWeightGrams']}g'}'),
          const Text('배달료: [결정 필요] 미정', style: TextStyle(color: Colors.grey, fontSize: 12)),
          Row(mainAxisAlignment: MainAxisAlignment.end, children: [
            TextButton(
              onPressed: _busy ? null : () => _run(() => api.post('/api/rider/offers/${o['offerId']}/reject', {'reason': '라이더 거절'}), ok: '거절했습니다'),
              child: const Text('거절'),
            ),
            FilledButton(
              onPressed: _busy || left == 0
                  ? null
                  : () => _run(() async {
                        await api.post('/api/rider/offers/${o['offerId']}/view');
                        final r = await api.post('/api/rider/offers/${o['offerId']}/accept') as Map<String, dynamic>;
                        if (mounted) {
                          await Navigator.of(context).push(MaterialPageRoute(
                              builder: (_) => DeliveryScreen(session: widget.session, deliveryId: r['deliveryId'] as String)));
                        }
                      }),
              child: const Text('수락'),
            ),
          ]),
        ]),
      ),
    );
  }
}
