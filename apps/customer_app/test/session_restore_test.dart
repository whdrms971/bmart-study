import 'dart:convert';

import 'package:customer_app/main.dart';
import 'package:delivery_common/delivery_common.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
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
  return '${b({'alg': 'HS256'})}.${b({'sub': 'C-TEST-01', 'role': 'CUSTOMER', 'exp': exp.millisecondsSinceEpoch ~/ 1000})}.sig';
}

Future<MemoryKv> stored(DateTime exp) async {
  final kv = MemoryKv();
  await SessionStore('CUSTOMER', storage: kv).save(Session(
      api: ApiClient('http://127.0.0.1:9', token: jwt(exp)), userId: 'C-TEST-01', role: 'CUSTOMER', baseUrl: 'http://127.0.0.1:9'));
  return kv;
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('만료 전 저장된 세션이 있으면 로그인 화면 없이 주문 목록으로', (tester) async {
    final kv = await stored(DateTime.now().add(const Duration(hours: 6)));
    await tester.pumpWidget(CustomerApp(store: SessionStore('CUSTOMER', storage: kv)));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.text('BARO-PICK 고객 테스트'), findsNothing);
    expect(find.textContaining('내 주문'), findsOneWidget);
    await tester.pumpWidget(const SizedBox()); // 화면 정리(타이머 해제)
  });

  testWidgets('만료된 세션은 지우고 로그인 화면', (tester) async {
    final kv = await stored(DateTime.now().subtract(const Duration(minutes: 5)));
    await tester.pumpWidget(CustomerApp(store: SessionStore('CUSTOMER', storage: kv)));
    await tester.pumpAndSettle();
    expect(find.text('BARO-PICK 고객 테스트'), findsOneWidget);
    expect(kv.m, isEmpty);
  });
}
