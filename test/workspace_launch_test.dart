import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:free_dextop/features_page.dart';
import 'package:free_dextop/main.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('app.freedextop/display');
  const youtube = 'com.google.android.youtube';
  const maps = 'com.google.android.apps.maps';
  final workspace = <String, dynamic>{
    'id': 'auto-workspace',
    'name': 'YouTube + Maps',
    'apps': [youtube, maps],
  };
  late List<MethodCall> calls;
  var nativeAutoActive = true;
  var snapshotAutoActive = true;
  var targetDisplayId = 47;
  Future<Object?> Function(MethodCall)? onLaunch;

  setUp(() {
    calls = [];
    nativeAutoActive = true;
    snapshotAutoActive = true;
    targetDisplayId = 47;
    onLaunch = null;
    SharedPreferences.setMockInitialValues({
      'setup_completed': true,
      'last_launched_app_version': '1.7.0',
      'multi_touch_upgrade_notice_acknowledged': true,
      'workspaces': jsonEncode([workspace]),
    });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'status':
              return {
                'active': false,
                'autoActive': snapshotAutoActive,
                'autoConnected': snapshotAutoActive,
                'privileged': true,
                'shizukuInstalled': true,
                'shizukuRunning': true,
                'shizukuGranted': true,
                'distributionChannel': 'play',
                'appVersion': '1.7.0',
              };
            case 'sessionState':
              return {
                'active': false,
                'autoActive': nativeAutoActive,
                'autoConnected': nativeAutoActive,
                'stopping': false,
              };
            case 'appLaunchDisplayId':
              return targetDisplayId;
            case 'launchApp':
              return await onLaunch?.call(call);
            case 'apps':
            case 'appsMetadata':
              return <dynamic>[];
            case 'appIcons':
            case 'recovery':
            case 'repairState':
              return <String, dynamic>{};
            case 'isFoldableDevice':
              return false;
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  List<Map<Object?, Object?>> launchArguments() => calls
      .where((call) => call.method == 'launchApp')
      .map((call) => Map<Object?, Object?>.from(call.arguments as Map))
      .toList();

  Future<void> pumpHome(WidgetTester tester) async {
    await tester.pumpWidget(const DextopApp());
    for (var frame = 0; frame < 12; frame++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
  }

  Future<void> finishBatch(
    WidgetTester tester,
    Future<void> batch,
    int appCount,
  ) async {
    await tester.pump();
    for (var index = 0; index <= appCount; index++) {
      await tester.pump(const Duration(milliseconds: 400));
    }
    await batch;
  }

  Future<dynamic> pumpFeatures(WidgetTester tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: DextopFeaturesPage(isRunning: true, launcherOnly: true),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    return tester.state(find.byType(DextopFeaturesPage));
  }

  testWidgets('Auto-only Home workspace Play launches both apps on one display', (
    tester,
  ) async {
    await pumpHome(tester);
    await tester.ensureVisible(find.text('Workspace'));
    await tester.tap(find.text('Workspace'));
    // Build the expanded child before advancing its AnimatedSize transition.
    await tester.pumpAndSettle(const Duration(milliseconds: 50));
    final workspaceTile = find.widgetWithText(ListTile, 'YouTube + Maps');
    final play = find.descendant(
      of: workspaceTile,
      matching: find.byIcon(Icons.play_arrow_rounded),
    );
    await Scrollable.ensureVisible(
      tester.element(workspaceTile),
      alignment: 0.5,
    );
    await tester.pumpAndSettle(const Duration(milliseconds: 50));
    expect(play.hitTestable(), findsOneWidget);
    await tester.tap(play.hitTestable());
    await tester.pump();
    for (var index = 0; index < 3; index++) {
      await tester.pump(const Duration(milliseconds: 400));
    }

    final launched = launchArguments();
    expect(launched, hasLength(2));
    expect(launched.map((args) => args['package']), [youtube, maps]);
    expect(launched.map((args) => args['displayId']), [47, 47]);
    expect(launched.map((args) => args['position']), ['half_left', 'half_right']);
    expect(launched.every((args) => !args.containsKey('bounds')), isTrue);
    expect(calls.where((call) => call.method == 'appLaunchDisplayId'), hasLength(1));
    expect(calls.where((call) => call.method == 'start'), isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('Home checks a newly started Auto session before starting phone', (
    tester,
  ) async {
    snapshotAutoActive = false;
    nativeAutoActive = false;
    await pumpHome(tester);
    nativeAutoActive = true;
    final dynamic state = tester.state(find.byType(HomeScreen));
    final batch = state.launchHomeWorkspace(workspace) as Future<void>;
    await finishBatch(tester, batch, 2);

    expect(launchArguments().map((args) => args['displayId']), [47, 47]);
    expect(calls.where((call) => call.method == 'sessionState'), isNotEmpty);
    expect(calls.where((call) => call.method == 'start'), isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('Features defaults use native relative grid positions', (tester) async {
    final dynamic state = await pumpFeatures(tester);
    final batch = state.launchPackages(<String>['one', 'two', 'three', 'four'])
        as Future<void>;
    await finishBatch(tester, batch, 4);

    final launched = launchArguments();
    expect(launched.map((args) => args['position']), [
      'grid_top_left',
      'grid_top_right',
      'grid_bottom_left',
      'grid_bottom_right',
    ]);
    expect(launched.every((args) => args['displayId'] == 47), isTrue);
    expect(launched.every((args) => !args.containsKey('bounds')), isTrue);
    expect(calls.where((call) => call.method == 'appLaunchDisplayId'), hasLength(1));
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('Features preserves saved bounds and positions before defaults', (
    tester,
  ) async {
    final dynamic state = await pumpFeatures(tester);
    final batch = state.launchPackages(
      <String>['one', 'two', 'three'],
      bounds: <String, dynamic>{
        'one': [20, 30, 400, 500],
        'three': [1, 2, 3],
      },
      positions: <String, dynamic>{
        'one': 'half_left',
        'two': 'right',
        'three': 'bottom_half',
      },
    ) as Future<void>;
    await finishBatch(tester, batch, 3);

    final launched = launchArguments();
    expect(launched[0]['bounds'], [20, 30, 400, 500]);
    expect(launched[0].containsKey('position'), isFalse);
    expect(launched[1]['position'], 'right');
    expect(launched[2]['position'], 'bottom_half');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('Features single app leaves layout to native default', (tester) async {
    final dynamic state = await pumpFeatures(tester);
    final batch = state.launchPackages(<String>[youtube]) as Future<void>;
    await finishBatch(tester, batch, 1);
    expect(launchArguments(), [
      {'package': youtube, 'displayId': 47},
    ]);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('Features keeps the pinned display and stops if Auto disappears', (
    tester,
  ) async {
    onLaunch = (call) async {
      final args = call.arguments as Map;
      if (args['package'] == 'one') {
        targetDisplayId = 12; // A phone display remains after Auto stops.
        return null;
      }
      if (args['displayId'] != targetDisplayId) {
        throw PlatformException(code: 'display', message: 'Auto display stopped');
      }
      return null;
    };
    final dynamic state = await pumpFeatures(tester);
    final batch = state.launchPackages(<String>['one', 'two', 'three'])
        as Future<void>;
    await finishBatch(tester, batch, 3);

    expect(launchArguments().map((args) => args['package']), ['one', 'two']);
    expect(launchArguments().map((args) => args['displayId']), [47, 47]);
    expect(calls.where((call) => call.method == 'appLaunchDisplayId'), hasLength(1));
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
