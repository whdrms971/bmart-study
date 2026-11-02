import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';

import 'orders_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initKakaoMap();
  runApp(const CustomerApp());
}

class CustomerApp extends StatefulWidget {
  const CustomerApp({super.key, this.store});

  /// 테스트에서 메모리 저장소 주입용. 기본은 기기 보안 저장소.
  final SessionStore? store;

  @override
  State<CustomerApp> createState() => _CustomerAppState();
}

class _CustomerAppState extends State<CustomerApp> {
  late final SessionStore _store = widget.store ?? SessionStore('CUSTOMER');
  Session? _session;
  bool _restoring = true;

  @override
  void initState() {
    super.initState();
    // 로그인 유지: 만료 전 토큰이 있으면 로그인 화면을 건너뛴다
    _store.restore().then((s) {
      if (!mounted) return;
      setState(() {
        _session = s;
        _restoring = false;
      });
    });
  }

  Future<void> _loggedIn(Session s) async {
    await _store.save(s);
    setState(() => _session = s);
  }

  Future<void> _logout() async {
    await _store.clear();
    setState(() => _session = null);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'BARO-PICK 고객 (TEST)',
      theme: ThemeData(colorSchemeSeed: Colors.teal, useMaterial3: true),
      home: _restoring
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : _session == null
              ? LoginScreen(
                  title: 'BARO-PICK 고객 테스트',
                  expectedRole: 'CUSTOMER',
                  defaultUserId: 'C-TEST-01',
                  onLoggedIn: _loggedIn,
                )
              : OrdersScreen(session: _session!, onLogout: _logout),
    );
  }
}
