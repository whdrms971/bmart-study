import 'package:shared_preferences/shared_preferences.dart';

/// 빌드 시 `--dart-define-from-file=kakao.local.json` 으로 주입된 값 + 로그인 화면에서 바꾼 서버 주소.
/// 키 값은 소스에 두지 않는다.
class AppConfig {
  static const String _definedBaseUrl = String.fromEnvironment('API_BASE_URL');
  static const String kakaoNativeAppKey = String.fromEnvironment('KAKAO_NATIVE_APP_KEY');
  static const _prefKey = 'api_base_url';

  static bool get hasKakaoKey => kakaoNativeAppKey.isNotEmpty;

  /// 저장된 주소 > 빌드 주입 주소. 둘 다 없으면 빈 문자열(로그인 화면에서 입력 요구).
  static Future<String> baseUrl() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_prefKey) ?? _definedBaseUrl;
  }

  static Future<void> saveBaseUrl(String url) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefKey, normalize(url));
  }

  static String normalize(String url) {
    var u = url.trim();
    while (u.endsWith('/')) {
      u = u.substring(0, u.length - 1);
    }
    return u;
  }

  /// http(s)://host:port → ws(s)://host:port/ws
  static String wsUrl(String baseUrl) {
    final u = Uri.parse(baseUrl);
    final scheme = u.scheme == 'https' ? 'wss' : 'ws';
    return u.replace(scheme: scheme, path: '/ws').toString();
  }
}
