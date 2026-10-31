import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

/// 서버 오류 응답 {code, message} 를 그대로 보존한다. 화면은 서버 판정을 기준으로 안내한다.
class ApiException implements Exception {
  ApiException(this.status, this.code, this.message);
  final int status;
  final String code;
  final String message;

  bool get isAuth => status == 401;
  bool get isNetwork => status == 0;

  @override
  String toString() => '$code: $message';
}

class ApiClient {
  ApiClient(this.baseUrl, {this.token, http.Client? client}) : _http = client ?? http.Client();

  final String baseUrl;
  String? token;
  final http.Client _http;
  static const _timeout = Duration(seconds: 12);

  Map<String, String> _headers({bool json = true}) => {
        if (json) 'Content-Type': 'application/json',
        if (token != null) 'Authorization': 'Bearer $token',
      };

  Future<dynamic> get(String path) => _send(() => _http.get(Uri.parse('$baseUrl$path'), headers: _headers(json: false)));

  Future<dynamic> post(String path, [Object? body]) => _send(() =>
      _http.post(Uri.parse('$baseUrl$path'), headers: _headers(), body: body == null ? null : jsonEncode(body)));

  Future<dynamic> put(String path, Object body) =>
      _send(() => _http.put(Uri.parse('$baseUrl$path'), headers: _headers(), body: jsonEncode(body)));

  /// 전달 증빙 업로드 (multipart). 이미지 판정은 서버가 파일 시그니처로 한다.
  Future<dynamic> postMultipart(String path, Map<String, String> fields, {String? fileField, Uint8List? bytes, String? filename}) {
    return _send(() async {
      final req = http.MultipartRequest('POST', Uri.parse('$baseUrl$path'))..fields.addAll(fields);
      if (token != null) req.headers['Authorization'] = 'Bearer $token';
      if (fileField != null && bytes != null) {
        req.files.add(http.MultipartFile.fromBytes(fileField, bytes, filename: filename ?? 'proof.jpg'));
      }
      return http.Response.fromStream(await _http.send(req));
    }, timeout: const Duration(seconds: 40));
  }

  Future<dynamic> _send(Future<http.Response> Function() call, {Duration timeout = _timeout}) async {
    http.Response res;
    try {
      res = await call().timeout(timeout);
    } on TimeoutException {
      // 타임아웃은 실패가 아니라 "결과 불명" — 호출측은 최신 상태를 재조회한다
      throw ApiException(0, 'TIMEOUT', '서버 응답 지연 — 결과를 다시 조회하세요');
    } catch (e) {
      throw ApiException(0, 'NETWORK', '서버에 연결할 수 없습니다 ($e)');
    }
    final text = utf8.decode(res.bodyBytes);
    dynamic data;
    if (text.isNotEmpty) {
      try {
        data = jsonDecode(text);
      } catch (_) {
        data = text;
      }
    }
    if (res.statusCode >= 200 && res.statusCode < 300) return data;
    if (data is Map) {
      throw ApiException(res.statusCode, '${data['code'] ?? 'HTTP_${res.statusCode}'}', '${data['message'] ?? ''}');
    }
    throw ApiException(res.statusCode, 'HTTP_${res.statusCode}', res.reasonPhrase ?? '');
  }

  Future<Map<String, dynamic>> login(String userId, String passcode) async {
    final r = await post('/api/auth/test-login', {'userId': userId, 'passcode': passcode}) as Map<String, dynamic>;
    token = r['accessToken'] as String;
    return r;
  }
}
