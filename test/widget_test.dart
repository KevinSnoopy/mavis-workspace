import 'package:flutter_test/flutter_test.dart';
import 'package:maodun_app/main.dart';

void main() {
  testWidgets('App loads smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const MaoDunApp());
    await tester.pump();

    // Verify the app starts without crashing
    expect(find.text('首页'), findsOneWidget);
  });
}
