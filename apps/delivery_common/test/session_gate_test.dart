import 'dart:convert';

import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

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

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('API 401 → 세션 로그아웃(저장 세션 삭제, 로그인 화면), 열린 상세 화면도 닫힘', (tester) async {
    final kv = MemoryKv();
    final store = SessionStore('RIDER', storage: kv);
    await store.save(Session(api: ApiClient('http://x', token: jwt(DateTime.now().add(const Duration(hours: 1)))), userId: 'R-TEST-01', role: 'RIDER', baseUrl: 'http://x'));
    Session? active;
    await tester.pumpWidget(SessionGate(
      role: 'RIDER', appTitle: 't', loginTitle: '로그인 화면', defaultUserId: 'R-TEST-01', theme: ThemeData(), store: store,
      home: (s, logout) {
        active = s;
        return Builder(builder: (ctx) => Scaffold(body: TextButton(
          onPressed: () => Navigator.of(ctx).push(MaterialPageRoute(builder: (_) => const Scaffold(body: Text('상세 화면')))),
          child: const Text('홈 화면'))));
      },
    ));
    await tester.pumpAndSettle();
    expect(find.text('홈 화면'), findsOneWidget);
    await tester.tap(find.text('홈 화면'));
    await tester.pumpAndSettle();
    expect(find.text('상세 화면'), findsOneWidget);

    // 이 세션의 ApiClient 가 401 을 받으면 로그아웃
    final unauthorized = ApiClient('http://x', token: active!.token, client: MockClient((_) async => http.Response('{"code":"UNAUTHORIZED"}', 401)));
    unauthorized.onUnauthorized = active!.api.onUnauthorized;
    await expectLater(unauthorized.get('/api/rider/me'), throwsA(isA<ApiException>()));
    await tester.pumpAndSettle();
    expect(find.text('로그인 화면'), findsOneWidget);
    expect(find.text('상세 화면'), findsNothing);
    expect(kv.m, isEmpty);
  });

  testWidgets('예전 세션의 늦은 401 은 새 세션을 로그아웃시키지 않는다', (tester) async {
    final kv = MemoryKv();
    final store = SessionStore('RIDER', storage: kv);
    final old = Session(api: ApiClient('http://x', token: jwt(DateTime.now().add(const Duration(hours: 1)))), userId: 'R-TEST-01', role: 'RIDER', baseUrl: 'http://x');
    await store.save(old);
    final sessions = <Session>[];
    VoidCallback? logoutOld;
    await tester.pumpWidget(SessionGate(
      role: 'RIDER', appTitle: 't', loginTitle: '로그인 화면', defaultUserId: 'R-TEST-01', theme: ThemeData(), store: store,
      home: (s, logout) {
        if (!sessions.contains(s)) sessions.add(s);
        logoutOld ??= logout;
        return Text('홈 ${sessions.length}');
      },
    ));
    await tester.pumpAndSettle();
    final first = sessions.first;
    final firstHook = first.api.onUnauthorized!;
    firstHook(); // 첫 세션 로그아웃
    await tester.pumpAndSettle();
    expect(find.text('로그인 화면'), findsOneWidget);
    // 같은 세션의 늦은 401 이 또 와도(중복) 문제 없고, 훅은 해제되어 있다
    expect(first.api.onUnauthorized, isNull);
    firstHook();
    logoutOld!();
    await tester.pumpAndSettle();
    expect(find.text('로그인 화면'), findsOneWidget);
  });
}
