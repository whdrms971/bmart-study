import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';

/// 테스트 주문: 시나리오(원거리 SIMULATED / 근거리 실GPS) 선택 → 배송지 선택(테스트 배송지 또는 카카오 주소 검색) → 상세주소.
/// 배송지는 주문 시점 스냅샷으로 확정되며, 고객 휴대폰의 현재 위치를 배송지로 쓰지 않는다.
class NewOrderScreen extends StatefulWidget {
  const NewOrderScreen({super.key, required this.session});
  final Session session;

  @override
  State<NewOrderScreen> createState() => _NewOrderScreenState();
}

class _Place {
  _Place(this.label, this.address, this.lat, this.lng);
  final String label;
  final String address;
  final double lat;
  final double lng;
}

class _NewOrderScreenState extends State<NewOrderScreen> {
  List<dynamic> _mfcs = [];
  List<dynamic> _presets = [];
  String? _mfcId;
  _Place? _place;
  final _query = TextEditingController();
  final _detail = TextEditingController();
  final _note = TextEditingController();
  int _weight = 3000;
  int _packages = 2;
  List<_Place> _results = [];
  bool _busy = false;
  String? _msg;

  ApiClient get api => widget.session.api;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    try {
      final m = await api.get('/test-api/mfcs') as List<dynamic>;
      final p = await api.get('/test-api/test-destinations') as List<dynamic>;
      setState(() {
        _mfcs = m;
        _presets = p;
        _mfcId = m.isNotEmpty ? m.first['id'] as String : null;
      });
    } on ApiException catch (e) {
      setState(() => _msg = e.toString());
    }
  }

  Future<void> _search() async {
    if (_query.text.trim().isEmpty) return;
    setState(() => _busy = true);
    try {
      final r = await api.get('/api/customer/places?query=${Uri.encodeQueryComponent(_query.text.trim())}') as List<dynamic>;
      setState(() {
        _results = r
            .map((e) => _Place('${e['name']}', '${(e['roadAddress'] as String?)?.isNotEmpty == true ? e['roadAddress'] : e['address']}',
                (e['lat'] as num).toDouble(), (e['lng'] as num).toDouble()))
            .toList();
        _msg = _results.isEmpty ? '검색 결과가 없습니다' : null;
      });
    } on ApiException catch (e) {
      setState(() => _msg = '주소 검색 실패(카카오 Local): $e');
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _submit() async {
    final p = _place;
    if (p == null || _mfcId == null) return;
    setState(() => _busy = true);
    try {
      final r = await api.post('/test-api/mock-oms/orders', {
        'order': {
          'mfcId': _mfcId,
          'latitude': p.lat,
          'longitude': p.lng,
          'address': p.address,
          'addressDetail': _detail.text.trim(),
          'totalWeightGrams': _weight,
          'packageCount': _packages,
          'customerNote': _note.text.trim(),
          'autoSeal': false,
        }
      }) as Map<String, dynamic>;
      if (mounted) Navigator.of(context).pop(r['orderId'] as String);
    } on ApiException catch (e) {
      setState(() => _msg = '주문 실패: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final mfc = _mfcs.cast<Map<String, dynamic>?>().firstWhere((m) => m?['id'] == _mfcId, orElse: () => null);
    final simulated = mfc?['locationMode'] == 'SIMULATED_ROUTE';
    return Scaffold(
      appBar: AppBar(title: const Text('테스트 주문')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text('시나리오 / 픽업 MFC', style: TextStyle(fontWeight: FontWeight.bold)),
          RadioGroup<String>(
            groupValue: _mfcId,
            onChanged: (v) => setState(() {
              _mfcId = v;
              _place = null;
            }),
            child: Column(children: [
              for (final m in _mfcs)
                RadioListTile<String>(
                  value: m['id'] as String,
                  title: Text('${m['id']}'),
                  subtitle: Text('${m['locationMode'] == 'SIMULATED_ROUTE' ? '원거리 · 위치 SIMULATED' : '근거리 · 실제 GPS'} · ${m['name']}'),
                ),
            ]),
          ),
          const Divider(),
          const Text('배송지', style: TextStyle(fontWeight: FontWeight.bold)),
          if (simulated)
            for (final d in _presets.where((d) => d['mfcId'] == _mfcId))
              ListTile(
                leading: const Icon(Icons.flag),
                title: Text('${d['label']}'),
                subtitle: Text('${d['address']} (FIXTURE 경로용 가상 주소)'),
                selected: _place?.label == d['label'],
                onTap: () => setState(() => _place = _Place('${d['label']}', '${d['address']}',
                    (d['latitude'] as num).toDouble(), (d['longitude'] as num).toDouble())),
              ),
          Row(children: [
            Expanded(child: TextField(controller: _query, decoration: const InputDecoration(labelText: '주소/장소 검색 (카카오)'),
                onSubmitted: (_) => _search())),
            IconButton(onPressed: _busy ? null : _search, icon: const Icon(Icons.search)),
          ]),
          for (final r in _results)
            ListTile(
              dense: true,
              title: Text(r.label),
              subtitle: Text(r.address),
              selected: identical(_place, r),
              onTap: () => setState(() => _place = r),
            ),
          if (_place != null)
            Card(child: ListTile(
              title: Text('선택: ${_place!.label}'),
              subtitle: Text('${_place!.address}\n${_place!.lat.toStringAsFixed(6)}, ${_place!.lng.toStringAsFixed(6)}'
                  '${simulated ? '\n※ 테스트 배송지 외 주소는 KAKAO_DIRECTIONS 경로로만 시뮬레이션 가능' : ''}'),
              isThreeLine: true,
            )),
          TextField(controller: _detail, decoration: const InputDecoration(labelText: '상세주소 (동·호수)')),
          TextField(controller: _note, decoration: const InputDecoration(labelText: '요청사항')),
          Row(children: [
            const Text('총중량'),
            Expanded(child: Slider(value: _weight.toDouble(), min: 500, max: 15000, divisions: 29,
                label: '$_weight g', onChanged: (v) => setState(() => _weight = v.round()))),
            Text('$_weight g'),
          ]),
          if (_weight > 10000) const Text('10,000g 초과 → 분할배송(배송 2건)', style: TextStyle(color: Colors.orange)),
          Row(children: [
            const Text('포장 수'),
            IconButton(onPressed: _packages > 1 ? () => setState(() => _packages--) : null, icon: const Icon(Icons.remove)),
            Text('$_packages'),
            IconButton(onPressed: _packages < 10 ? () => setState(() => _packages++) : null, icon: const Icon(Icons.add)),
          ]),
          const SizedBox(height: 12),
          FilledButton(onPressed: _busy || _place == null || _mfcId == null ? null : _submit, child: const Text('테스트 주문하기')),
          if (_msg != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(_msg!, style: const TextStyle(color: Colors.red))),
        ],
      ),
    );
  }
}
