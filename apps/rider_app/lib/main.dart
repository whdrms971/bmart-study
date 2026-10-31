import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';

import 'home_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initKakaoMap();
  runApp(const RiderApp());
}

class RiderApp extends StatefulWidget {
  const RiderApp({super.key});

  @override
  State<RiderApp> createState() => _RiderAppState();
}

class _RiderAppState extends State<RiderApp> {
  Session? _session;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'BARO-PICK 라이더 (TEST)',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: _session == null
          ? LoginScreen(
              title: 'BARO-PICK 라이더 테스트',
              expectedRole: 'RIDER',
              defaultUserId: 'R-TEST-01',
              onLoggedIn: (s) => setState(() => _session = s),
            )
          : HomeScreen(session: _session!, onLogout: () => setState(() => _session = null)),
    );
  }
}
