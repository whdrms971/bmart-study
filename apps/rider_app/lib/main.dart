import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';

import 'home_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initKakaoMap();
  runApp(const RiderApp());
}

class RiderApp extends StatefulWidget {
  const RiderApp({super.key, this.store});

  /// 테스트에서 메모리 저장소 주입용. 기본은 기기 보안 저장소.
  final SessionStore? store;

  @override
  State<RiderApp> createState() => _RiderAppState();
}

class _RiderAppState extends State<RiderApp> {
  late final SessionStore _store = widget.store ?? SessionStore('RIDER');
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
      title: 'BARO-PICK 라이더 (TEST)',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: _restoring
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : _session == null
              ? LoginScreen(
                  title: 'BARO-PICK 라이더 테스트',
                  expectedRole: 'RIDER',
                  defaultUserId: 'R-TEST-01',
                  onLoggedIn: _loggedIn,
                )
              : HomeScreen(session: _session!, onLogout: _logout),
    );
  }
}
