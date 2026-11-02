import 'package:flutter_test/flutter_test.dart';
import 'package:rider_app/main.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('로그인 화면이 라이더 테스트 계정 기본값으로 표시된다', (tester) async {
    SharedPreferences.setMockInitialValues({});
    FlutterSecureStorage.setMockInitialValues({}); // 저장된 세션 없음 → 로그인 화면
    await tester.pumpWidget(const RiderApp());
    await tester.pumpAndSettle();
    expect(find.text('BARO-PICK 라이더 테스트'), findsOneWidget);
    expect(find.text('R-TEST-01'), findsOneWidget);
  });
}
