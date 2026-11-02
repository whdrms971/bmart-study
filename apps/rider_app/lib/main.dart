import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';
import 'package:kakao_flutter_sdk_navi/kakao_flutter_sdk_navi.dart';

import 'home_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initKakaoMap();
  // 카카오내비 길안내(공식 SDK). 지도 SDK 와 같은 네이티브 앱 키를 쓴다
  if (AppConfig.hasKakaoKey) KakaoSdk.init(nativeAppKey: AppConfig.kakaoNativeAppKey);
  runApp(const RiderApp());
}

class RiderApp extends StatelessWidget {
  const RiderApp({super.key, this.store});

  /// 테스트에서 메모리 저장소 주입용. 기본은 기기 보안 저장소.
  final SessionStore? store;

  @override
  Widget build(BuildContext context) {
    return SessionGate(
      role: 'RIDER',
      appTitle: 'BARO-PICK 라이더 (TEST)',
      loginTitle: 'BARO-PICK 라이더 테스트',
      defaultUserId: 'R-TEST-01',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      store: store,
      home: (session, logout) => HomeScreen(session: session, onLogout: logout),
    );
  }
}
