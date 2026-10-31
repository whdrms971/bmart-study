import 'package:customer_app/main.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('로그인 화면이 고객 테스트 계정 기본값으로 표시된다', (tester) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const CustomerApp());
    await tester.pumpAndSettle();
    expect(find.text('BARO-PICK 고객 테스트'), findsOneWidget);
    expect(find.text('C-TEST-01'), findsOneWidget);
    expect(find.text('로그인'), findsOneWidget);
  });
}
