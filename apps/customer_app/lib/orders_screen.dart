import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';

import 'new_order_screen.dart';
import 'tracking_screen.dart';

/// 내 테스트 주문 목록 (Mock OMS). 주문 상태는 OMS 정본, 배송 상태는 추적 화면에서 Delivery 정본을 본다.
class OrdersScreen extends StatefulWidget {
  const OrdersScreen({super.key, required this.session, required this.onLogout});
  final Session session;
  final VoidCallback onLogout;

  @override
  State<OrdersScreen> createState() => _OrdersScreenState();
}

class _OrdersScreenState extends State<OrdersScreen> {
  /// 닫힌 화면(로그아웃·뒤로가기)에서 늦게 끝난 요청이 setState 를 호출해도 무시한다
  @override
  void setState(VoidCallback fn) {
    if (mounted) super.setState(fn);
  }

  List<dynamic> _orders = [];
  String? _error;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      final r = await widget.session.api.get('/test-api/mock-oms/orders') as List<dynamic>;
      setState(() {
        _orders = r;
        _error = null;
      });
    } on ApiException catch (e) {
      if (e.isAuth) return widget.onLogout();
      setState(() => _error = e.userMessage);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('내 주문 · ${widget.session.userId}'),
        actions: [
          IconButton(onPressed: _load, icon: const Icon(Icons.refresh), tooltip: '새로고침'),
          IconButton(onPressed: widget.onLogout, icon: const Icon(Icons.logout), tooltip: '로그아웃'),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          final created = await Navigator.of(context).push<String>(
            MaterialPageRoute(builder: (_) => NewOrderScreen(session: widget.session)),
          );
          await _load();
          if (created != null && context.mounted) {
            Navigator.of(context).push(MaterialPageRoute(builder: (_) => TrackingScreen(session: widget.session, orderId: created)));
          }
        },
        icon: const Icon(Icons.add_shopping_cart),
        label: const Text('테스트 주문'),
      ),
      body: RefreshIndicator(
        onRefresh: _load,
        child: ListView(
          children: [
            if (_loading) const LinearProgressIndicator(),
            if (_error != null) Padding(padding: const EdgeInsets.all(12), child: Text(_error!, style: const TextStyle(color: Colors.red))),
            if (_orders.isEmpty && !_loading) const Padding(padding: EdgeInsets.all(24), child: Text('주문이 없습니다. "테스트 주문"을 눌러 시작하세요.')),
            for (final o in _orders)
              ListTile(
                title: Text('${o['orderId']}'),
                subtitle: Text('${o['address']}\n배송 ${(o['deliveries'] as List).length}건${o['split'] == true ? ' (분할)' : ''} · ${o['mfcId']}'),
                isThreeLine: true,
                trailing: Chip(label: Text('${o['status']}')),
                onTap: () => Navigator.of(context)
                    .push(MaterialPageRoute(builder: (_) => TrackingScreen(session: widget.session, orderId: o['orderId'] as String)))
                    .then((_) => _load()),
              ),
          ],
        ),
      ),
    );
  }
}
