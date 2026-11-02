import 'dart:async';

import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';

/// 고객 화면의 "도착 예정" 문구. 남은 시간 계산 결과(카카오 경로)만 "약 N분 (HH:mm 도착 예상)"으로 바꿔 보여주고,
/// 배송지 근처(provider=NEAR_DESTINATION)·확인 불가·출발 전·종료 등은 서버가 보낸 문구를 그대로 보여준다
/// (예: "약 0분" 대신 "배송지 근처 (직선 4m) — 라이더의 도착·전달 처리를 기다리는 중").
String etaLabel(Map<String, dynamic> eta) {
  final message = '${eta['message'] ?? ''}';
  final seconds = eta['etaSeconds'] as num?;
  if (eta['status'] != 'OK' || eta['provider'] == 'NEAR_DESTINATION' || seconds == null) {
    return message.isNotEmpty ? message : '도착 예상 시간을 확인할 수 없습니다';
  }
  final arrival = DateTime.tryParse('${eta['estimatedArrivalAt']}')?.toLocal();
  final at = arrival == null ? '' : ' (${arrival.hour}:${arrival.minute.toString().padLeft(2, '0')} 도착 예상)';
  return '약 ${(seconds / 60).ceil()}분$at · ${hhmm(eta['computedAt'] as String?)} 계산';
}

/// 주문 배송 추적. 데이터는 전부 Delivery 서버(REST 최초 조회 + STOMP 구독)에서 온다 — 화면에서 마커를 임의로 움직이지 않는다.
class TrackingScreen extends StatefulWidget {
  const TrackingScreen({super.key, required this.session, required this.orderId});
  final Session session;
  final String orderId;

  @override
  State<TrackingScreen> createState() => _TrackingScreenState();
}

class _TrackingScreenState extends State<TrackingScreen> {
  /// 닫힌 화면(로그아웃·뒤로가기)에서 늦게 끝난 요청이 setState 를 호출해도 무시한다
  @override
  void setState(VoidCallback fn) {
    if (mounted) super.setState(fn);
  }

  final Map<String, Map<String, dynamic>> _deliveries = {};
  List<String> _order = [];
  String? _selected;
  RealtimeClient? _rt;
  RealtimeState _rtState = RealtimeState.disconnected;
  StreamSubscription<RealtimeState>? _rtSub;
  Timer? _ticker;
  Timer? _poll;
  bool _follow = true;
  int _focus = 0;
  String? _error;

  static const _terminal = {'DELIVERED', 'CANCELLED'};

  @override
  void initState() {
    super.initState();
    _refresh().then((_) => _connect());
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {})); // 경과시간 표시 갱신
    _poll = Timer.periodic(const Duration(seconds: 20), (_) => _refresh()); // 실시간 누락 대비 백업 조회
  }

  Future<void> _refresh() async {
    try {
      final r = await widget.session.api.get('/api/customer/orders/${widget.orderId}/tracking') as Map<String, dynamic>;
      final list = (r['deliveries'] as List).cast<Map<String, dynamic>>();
      setState(() {
        for (final d in list) {
          _apply(d);
        }
        _order = list.map((d) => d['deliveryId'] as String).toList();
        _selected ??= _order.isNotEmpty ? _order.first : null;
        _error = null;
      });
      _maybeStop();
    } on ApiException catch (e) {
      setState(() => _error = e.status == 404 ? '주문을 찾을 수 없습니다(본인 주문만 조회 가능)' : e.userMessage);
    }
  }

  /// 서버 serverTime 이 더 오래된 메시지는 버린다 (재연결·지연 도착 시 역행 방지)
  void _apply(Map<String, dynamic> d) {
    final id = d['deliveryId'] as String;
    final cur = _deliveries[id];
    if (cur != null && isOlder(d['serverTime'] as String?, cur['serverTime'] as String?)) return;
    _deliveries[id] = d;
  }

  void _connect() {
    if (_allTerminal) return;
    _rt = RealtimeClient(
      baseUrl: widget.session.baseUrl,
      token: widget.session.token,
      topic: '/topic/orders/${widget.orderId}',
      onConnected: _refresh, // (재)연결 시 최신 상태 재조회
      onMessage: (m) {
        final data = m['data'];
        if (m['type'] == 'DELIVERY_UPDATED' && data is Map<String, dynamic>) {
          setState(() => _apply(data));
          _maybeStop();
        }
      },
    );
    _rtSub = _rt!.states.listen((s) => setState(() => _rtState = s));
    _rt!.connect();
  }

  bool get _allTerminal => _deliveries.isNotEmpty && _deliveries.values.every((d) => _terminal.contains(d['status']));

  /// 모든 배송 종료 시 위치 구독 해제
  void _maybeStop() {
    if (_allTerminal && _rt != null) {
      _rtSub?.cancel();
      _rt?.dispose();
      _rt = null;
      setState(() => _rtState = RealtimeState.disconnected);
    }
  }

  @override
  void dispose() {
    _ticker?.cancel();
    _poll?.cancel();
    _rtSub?.cancel();
    _rt?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final d = _selected == null ? null : _deliveries[_selected];
    return Scaffold(
      appBar: AppBar(title: Text('배송 추적 · ${widget.orderId}'), actions: [
        Padding(padding: const EdgeInsets.only(right: 12), child: _ConnChip(state: _rtState, ended: _allTerminal)),
      ]),
      body: d == null
          ? Center(child: _error != null ? Text(_error!, style: const TextStyle(color: Colors.red)) : const CircularProgressIndicator())
          : Column(children: [
              if (_order.length > 1)
                SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(children: [
                    for (var i = 0; i < _order.length; i++)
                      Padding(
                        padding: const EdgeInsets.all(4),
                        child: ChoiceChip(
                          label: Text('배송 ${i + 1}/${_order.length} · ${_deliveries[_order[i]]?['statusLabel']}'),
                          selected: _order[i] == _selected,
                          onSelected: (_) => setState(() {
                            _selected = _order[i];
                            _follow = true;
                          }),
                        ),
                      ),
                  ]),
                ),
              Expanded(child: _map(d)),
              _panel(d),
            ]),
    );
  }

  Widget _map(Map<String, dynamic> d) {
    final dest = d['destination'] as Map<String, dynamic>;
    final rider = d['rider'] as Map<String, dynamic>?;
    return Stack(children: [
      DeliveryMap(
        key: ValueKey(d['deliveryId']),
        destination: GeoPoint((dest['latitude'] as num).toDouble(), (dest['longitude'] as num).toDouble()),
        rider: rider == null ? null : GeoPoint((rider['latitude'] as num).toDouble(), (rider['longitude'] as num).toDouble()),
        follow: _follow,
        focusRequest: _focus,
        onUserGesture: () {
          if (_follow) setState(() => _follow = false); // 사용자가 지도를 조작하면 자동 추적 중단
        },
      ),
      Positioned(
        right: 12,
        bottom: 12,
        child: Column(children: [
          if (rider != null)
            FilledButton.icon(
              onPressed: () => setState(() {
                _follow = true;
                _focus++;
              }),
              icon: const Icon(Icons.two_wheeler),
              label: const Text('라이더 위치 보기'),
            ),
          const SizedBox(height: 6),
          FilterChip(label: Text(_follow ? '자동 추적 ON' : '자동 추적 OFF'), selected: _follow, onSelected: (v) => setState(() => _follow = v)),
        ]),
      ),
      if (d['simulated'] == true)
        const Positioned(left: 12, top: 12, child: Chip(label: Text('SIMULATED · 테스트 위치'), backgroundColor: Colors.purpleAccent)),
    ]);
  }

  Widget _panel(Map<String, dynamic> d) {
    final rider = d['rider'] as Map<String, dynamic>?;
    final eta = d['eta'] as Map<String, dynamic>;
    final pk = d['packages'] as Map<String, dynamic>;
    final dest = d['destination'] as Map<String, dynamic>;
    final serverTime = DateTime.tryParse('${d['serverTime']}');
    final received = rider == null ? null : DateTime.tryParse('${rider['receivedAt']}');
    // 서버 기준 경과 + 서버 응답 이후 로컬 경과
    int? age;
    if (rider != null) {
      final base = (rider['ageSeconds'] as num).toInt();
      age = serverTime == null ? base : base + DateTime.now().toUtc().difference(serverTime.toUtc()).inSeconds.clamp(0, 86400);
    }
    final stale = age != null && age > 30;
    final etaText = etaLabel(eta);
    return Material(
      elevation: 8,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
          Row(children: [
            Text('${d['statusLabel']}', style: Theme.of(context).textTheme.titleLarge),
            const Spacer(),
            Text('포장 ${pk['handedOver']}/${pk['total']} 인계'),
          ]),
          const SizedBox(height: 4),
          Text('배송지: ${dest['address']} ${dest['addressDetail'] ?? ''}'),
          Text('도착 예정: $etaText'),
          if (d['riderLocationVisible'] != true)
            Text(_terminal.contains(d['status']) ? '배송이 종료되어 위치 공유가 끝났습니다' : '상품 픽업 후 라이더 위치가 표시됩니다',
                style: const TextStyle(color: Colors.grey))
          else if (rider == null)
            const Text('위치 확인 중 (아직 수신된 위치 없음)', style: TextStyle(color: Colors.orange))
          else
            Text('라이더 위치 마지막 갱신: ${ago(age)} (${rider['source'] == 'SIMULATED_ROUTE' ? 'SIMULATED' : 'GPS'})'
                '${stale ? ' — 위치 갱신 지연, 실제 위치와 다를 수 있음' : ''}',
                style: TextStyle(color: stale ? Colors.orange : null)),
          if (received != null) Text('수신 ${hhmm(rider!['receivedAt'] as String?)}', style: const TextStyle(color: Colors.grey, fontSize: 12)),
          if (_error != null) Text(_error!, style: const TextStyle(color: Colors.red)),
        ]),
      ),
    );
  }
}

class _ConnChip extends StatelessWidget {
  const _ConnChip({required this.state, required this.ended});
  final RealtimeState state;
  final bool ended;

  @override
  Widget build(BuildContext context) {
    if (ended) return const Chip(label: Text('종료'));
    return switch (state) {
      RealtimeState.connected => const Chip(label: Text('실시간'), avatar: Icon(Icons.circle, color: Colors.green, size: 12)),
      RealtimeState.connecting => const Chip(label: Text('연결 중')),
      RealtimeState.disconnected => const Chip(label: Text('재연결 대기'), avatar: Icon(Icons.circle, color: Colors.orange, size: 12)),
    };
  }
}
