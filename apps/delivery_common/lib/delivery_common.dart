/// 고객·라이더 앱 공통: 서버 설정, REST 클라이언트, STOMP 실시간 연결, 로그인 화면.
library;

import 'src/config.dart';

export 'src/config.dart';
export 'src/api_client.dart';
export 'src/realtime.dart';
export 'src/session.dart';
export 'src/session_gate.dart';
export 'src/login_screen.dart';
export 'src/format.dart';
export 'src/delivery_map.dart';

import 'package:kakao_map_sdk/kakao_map_sdk.dart';

/// 앱 시작 시 1회 호출. 키가 없으면 지도 대신 좌표 표시로 동작한다.
Future<void> initKakaoMap() async {
  if (AppConfig.hasKakaoKey) await KakaoMapSdk.instance.initialize(AppConfig.kakaoNativeAppKey);
}
