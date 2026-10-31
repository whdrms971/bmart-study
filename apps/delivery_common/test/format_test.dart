import 'package:delivery_common/delivery_common.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('wsUrl 변환', () {
    expect(AppConfig.wsUrl('http://192.168.0.10:8080'), 'ws://192.168.0.10:8080/ws');
    expect(AppConfig.wsUrl('https://dev.example.com'), 'wss://dev.example.com/ws');
    expect(AppConfig.normalize(' http://a:1/// '), 'http://a:1');
  });

  test('과거 메시지 판정', () {
    expect(isOlder('2026-10-31T10:00:00Z', '2026-10-31T10:00:01Z'), isTrue);
    expect(isOlder('2026-10-31T10:00:02Z', '2026-10-31T10:00:01Z'), isFalse);
    expect(isOlder(null, '2026-10-31T10:00:01Z'), isFalse);
  });

  test('표시 포맷', () {
    expect(ago(5), '5초 전');
    expect(ago(125), '2분 전');
    expect(meters(1530), '1.5km');
    expect(meters(80), '80m');
  });
}
