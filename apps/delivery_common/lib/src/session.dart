import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'api_client.dart';

/// 로그인된 세션.
class Session {
  Session({required this.api, required this.userId, required this.role, required this.baseUrl});

  final ApiClient api;
  final String userId;
  final String role;
  final String baseUrl;

  String get token => api.token ?? '';
}

/// 세션 저장소 추상화 (테스트에서 메모리 구현으로 교체).
abstract class SecureKeyValue {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

/// Android Keystore 로 암호화되는 보안 저장소.
class PlatformSecureKeyValue implements SecureKeyValue {
  PlatformSecureKeyValue([FlutterSecureStorage? storage]) : _s = storage ?? const FlutterSecureStorage();
  final FlutterSecureStorage _s;

  @override
  Future<String?> read(String key) => _s.read(key: key);
  @override
  Future<void> write(String key, String value) => _s.write(key: key, value: value);
  @override
  Future<void> delete(String key) => _s.delete(key: key);
}

/// 로그인 유지 (2026-11-02 결정): 토큰을 기기 보안 저장소에 두고, 만료(JWT exp) 전까지 앱을 다시 켜도 자동 로그인.
/// - 만료됐거나 형식이 깨진 토큰은 복원하지 않고 지운다.
/// - 서버가 401 을 주면(서명 키 변경·만료) 화면이 로그아웃하며 [clear] 를 호출한다.
class SessionStore {
  SessionStore(this.role, {SecureKeyValue? storage, DateTime Function()? now})
      : _kv = storage ?? PlatformSecureKeyValue(),
        _now = now ?? DateTime.now;

  final String role;
  final SecureKeyValue _kv;
  final DateTime Function() _now;

  /// 저장소 읽기 제한. 첫 읽기(Keystore 복호화)는 저사양·에뮬레이터(ARM 변환)에서 3초를 넘기도 해 3초 제한은 복원을 실패시켰다.
  static const readTimeout = Duration(seconds: 15);

  /// 만료 직전 토큰으로 시작하면 바로 401 이 나므로 여유를 둔다.
  static const expirySkew = Duration(minutes: 1);

  String get _key => 'baropick.session.$role';

  Future<void> save(Session s) async {
    await _kv.write(_key, jsonEncode({'baseUrl': s.baseUrl, 'token': s.token, 'userId': s.userId, 'role': s.role}));
  }

  Future<Session?> restore() async {
    final String? raw;
    try {
      // 저장소 응답이 없을 때 앱 시작이 멈추지 않도록 시간 제한
      raw = await _kv.read(_key).timeout(readTimeout);
    } catch (_) {
      return null; // 보안 저장소 사용 불가·응답 없음 → 로그인 화면
    }
    if (raw == null) return null;
    try {
      final m = jsonDecode(raw) as Map<String, dynamic>;
      final token = m['token'] as String;
      final exp = tokenExpiry(token);
      if (m['role'] != role || exp == null || !_now().isBefore(exp.subtract(expirySkew))) {
        await clear();
        return null;
      }
      final baseUrl = m['baseUrl'] as String;
      return Session(api: ApiClient(baseUrl, token: token), userId: m['userId'] as String, role: role, baseUrl: baseUrl);
    } catch (_) {
      await clear();
      return null;
    }
  }

  Future<void> clear() => _kv.delete(_key);

  /// JWT payload 의 exp(초) → 시각. 서명 검증은 서버가 한다(여기서는 만료 판단만).
  static DateTime? tokenExpiry(String token) {
    final parts = token.split('.');
    if (parts.length != 3) return null;
    try {
      final payload = jsonDecode(utf8.decode(base64Url.decode(base64Url.normalize(parts[1])))) as Map<String, dynamic>;
      final exp = payload['exp'];
      if (exp is! num) return null;
      return DateTime.fromMillisecondsSinceEpoch((exp * 1000).toInt(), isUtc: true);
    } catch (_) {
      return null;
    }
  }
}
