import 'dart:convert';

import 'package:delivery_common/delivery_common.dart';
import 'package:flutter_test/flutter_test.dart';

class MemoryKv implements SecureKeyValue {
  final Map<String, String> m = {};
  @override
  Future<String?> read(String key) async => m[key];
  @override
  Future<void> write(String key, String value) async => m[key] = value;
  @override
  Future<void> delete(String key) async => m.remove(key);
}

String jwt(DateTime exp) {
  String b(Map<String, dynamic> j) => base64Url.encode(utf8.encode(jsonEncode(j))).replaceAll('=', '');
  return '${b({'alg': 'HS256'})}.${b({'sub': 'R-TEST-01', 'role': 'RIDER', 'exp': exp.millisecondsSinceEpoch ~/ 1000})}.sig';
}

Session session(String token) =>
    Session(api: ApiClient('http://10.0.2.2:18080', token: token), userId: 'R-TEST-01', role: 'RIDER', baseUrl: 'http://10.0.2.2:18080');

void main() {
  final now = DateTime.utc(2026, 11, 2, 12);

  test('저장 → 복원: 같은 사용자·서버·토큰', () async {
    final kv = MemoryKv();
    final store = SessionStore('RIDER', storage: kv, now: () => now);
    final token = jwt(now.add(const Duration(hours: 12)));
    await store.save(session(token));
    final s = await store.restore();
    expect(s, isNotNull);
    expect(s!.userId, 'R-TEST-01');
    expect(s.baseUrl, 'http://10.0.2.2:18080');
    expect(s.token, token);
  });

  test('만료(또는 1분 이내 만료) 토큰은 복원하지 않고 삭제', () async {
    final kv = MemoryKv();
    final store = SessionStore('RIDER', storage: kv, now: () => now);
    await store.save(session(jwt(now.add(const Duration(seconds: 30)))));
    expect(await store.restore(), isNull);
    expect(kv.m, isEmpty);
  });

  test('다른 역할·깨진 데이터는 복원하지 않음', () async {
    final kv = MemoryKv();
    await SessionStore('RIDER', storage: kv, now: () => now).save(session(jwt(now.add(const Duration(hours: 1)))));
    expect(await SessionStore('CUSTOMER', storage: kv, now: () => now).restore(), isNull);
    kv.m['baropick.session.RIDER'] = 'not-json';
    expect(await SessionStore('RIDER', storage: kv, now: () => now).restore(), isNull);
    expect(kv.m, isEmpty);
  });

  test('로그아웃(clear) 후 복원 없음', () async {
    final kv = MemoryKv();
    final store = SessionStore('RIDER', storage: kv, now: () => now);
    await store.save(session(jwt(now.add(const Duration(hours: 1)))));
    await store.clear();
    expect(await store.restore(), isNull);
  });

  test('JWT exp 해석', () {
    final exp = now.add(const Duration(hours: 3));
    expect(SessionStore.tokenExpiry(jwt(exp)), exp);
    expect(SessionStore.tokenExpiry('abc'), isNull);
  });
}
