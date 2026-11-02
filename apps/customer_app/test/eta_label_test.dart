import 'package:customer_app/tracking_screen.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('배송지 근처(NEAR_DESTINATION)는 "약 0분" 대신 서버 문구를 그대로 보여준다', () {
    const msg = '배송지 근처 (직선 4m) — 라이더의 도착·전달 처리를 기다리는 중';
    final label = etaLabel({
      'status': 'OK', 'etaSeconds': 0, 'distanceMeters': 4, 'provider': 'NEAR_DESTINATION',
      'computedAt': '2026-11-02T07:19:38Z', 'estimatedArrivalAt': '2026-11-02T07:19:38Z', 'message': msg,
    });
    expect(label, msg);
    expect(label, isNot(contains('0분')));
  });

  test('카카오 경로 ETA 는 남은 분·도착 예상 시각·계산 시각으로 표시', () {
    final label = etaLabel({
      'status': 'OK', 'etaSeconds': 700, 'provider': 'KAKAO_DIRECTIONS',
      'computedAt': '2026-11-02T07:17:37Z', 'estimatedArrivalAt': '2026-11-02T07:29:17Z',
      'message': '카카오모빌리티 자동차 경로 기준 (실시각 기준, 시뮬레이션 배속 미반영)',
    });
    expect(label, startsWith('약 12분 ('));
    expect(label, contains('도착 예상'));
    expect(label, contains('계산'));
  });

  test('확인 불가·출발 전·종료는 서버 문구, 문구가 없으면 기본 안내', () {
    expect(etaLabel({'status': 'UNAVAILABLE', 'message': '도착 예상 시간을 지금 확인할 수 없습니다. 라이더 위치는 계속 갱신됩니다'}),
        '도착 예상 시간을 지금 확인할 수 없습니다. 라이더 위치는 계속 갱신됩니다');
    expect(etaLabel({'status': 'NOT_APPLICABLE', 'message': '배송이 종료되었습니다'}), '배송이 종료되었습니다');
    expect(etaLabel({'status': 'OK', 'etaSeconds': null, 'message': null}), '도착 예상 시간을 확인할 수 없습니다');
  });
}
