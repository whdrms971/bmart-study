import 'api_client.dart';

/// 로그인된 세션 (토큰은 메모리에만 보관 — 앱 재시작 시 재로그인).
class Session {
  Session({required this.api, required this.userId, required this.role, required this.baseUrl});

  final ApiClient api;
  final String userId;
  final String role;
  final String baseUrl;

  String get token => api.token ?? '';
}
