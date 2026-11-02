import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';

import 'orders_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initKakaoMap();
  runApp(const CustomerApp());
}

class CustomerApp extends StatelessWidget {
  const CustomerApp({super.key, this.store});

  /// 테스트에서 메모리 저장소 주입용. 기본은 기기 보안 저장소.
  final SessionStore? store;

  @override
  Widget build(BuildContext context) {
    return SessionGate(
      role: 'CUSTOMER',
      appTitle: 'BARO-PICK 고객 (TEST)',
      loginTitle: 'BARO-PICK 고객 테스트',
      defaultUserId: 'C-TEST-01',
      theme: ThemeData(colorSchemeSeed: Colors.teal, useMaterial3: true),
      store: store,
      home: (session, logout) => OrdersScreen(session: session, onLogout: logout),
    );
  }
}
