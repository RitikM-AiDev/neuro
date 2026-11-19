import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:neuro_apps/main.dart';

void main() {
  testWidgets('Neuro app smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const NeuroControllerApp());
    expect(find.byType(MaterialApp), findsWidgets);
  });
}
