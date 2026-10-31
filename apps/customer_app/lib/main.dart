import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';

import 'orders_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initKakaoMap();
  runApp(const CustomerApp());
}

class CustomerApp extends StatefulWidget {
  const CustomerApp({super.key});

  @override
  State<CustomerApp> createState() => _CustomerAppState();
}

class _CustomerAppState extends State<CustomerApp> {
  Session? _session;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'BARO-PICK 고객 (TEST)',
      theme: ThemeData(colorSchemeSeed: Colors.teal, useMaterial3: true),
      home: _session == null
          ? LoginScreen(
              title: 'BARO-PICK 고객 테스트',
              expectedRole: 'CUSTOMER',
              defaultUserId: 'C-TEST-01',
              onLoggedIn: (s) => setState(() => _session = s),
            )
          : OrdersScreen(session: _session!, onLogout: () => setState(() => _session = null)),
    );
  }
}
