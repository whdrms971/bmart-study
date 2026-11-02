import 'dart:convert';

import 'package:delivery_common/delivery_common.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  test('네트워크 오류: 고객 문구에 예외 원문이 없고, 원문은 detail 에만 남는다', () async {
    final client = MockClient((_) async => throw http.ClientException('SocketException: Connection failed (OS Error: Network is unreachable, errno = 101)'));
    final api = ApiClient('http://10.0.2.2:18080', client: client);
    await expectLater(
      api.get('/api/customer/orders/X/tracking'),
      throwsA(isA<ApiException>()
          .having((e) => e.code, 'code', 'NETWORK')
          .having((e) => e.userMessage, 'userMessage', allOf(contains('인터넷 연결'), isNot(contains('Socket')), isNot(contains('errno')), isNot(contains('NETWORK'))))
          .having((e) => e.detail, 'detail', contains('errno = 101'))),
    );
  });

  test('5xx 는 일반 안내 문구(서버 예외 원문 미표시)', () async {
    final api = ApiClient('http://x', client: MockClient((_) async => http.Response('{"code":"INTERNAL","message":"NullPointerException at Foo"}', 500)));
    await expectLater(
      api.get('/a'),
      throwsA(isA<ApiException>().having((e) => e.userMessage, 'userMessage', allOf(contains('일시적인 문제'), isNot(contains('NullPointer'))))),
    );
  });

  test('4xx 는 서버가 준 안내 문구 그대로', () async {
    final api = ApiClient('http://x',
        client: MockClient((_) async => http.Response.bytes(utf8.encode('{"code":"ORDER_NOT_FOUND","message":"주문을 찾을 수 없습니다"}'), 404)));
    await expectLater(api.get('/a'), throwsA(isA<ApiException>().having((e) => e.userMessage, 'userMessage', '주문을 찾을 수 없습니다')));
  });
}
