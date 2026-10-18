import 'dart:async';

import 'package:fetchy/main.dart';
import 'package:fetchy/native_bridge.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];
  var shared = '';
  var failAnalyze = false;
  var failEnsureRoot = false;
  Completer<void>? snapshotGate;
  Completer<dynamic>? analysisGate;
  Completer<dynamic>? previewGate;
  final snapshot = <String, dynamic>{
    'jobs': [],
    'folders': [],
    'files': [],
    'settings': {'rootUri': 'content://test/root', 'rootName': 'Fetchy'},
    'cookies': [],
    'engine': {},
  };
  final analysis = {
    'sourceUrl': 'https://example.com/video',
    'platform': 'youtube',
    'title': '여행 기록',
    'assets': [
      {
        'id': 'asset',
        'title': '바다',
        'kind': 'video',
        'formats': [
          {
            'id': 'v1',
            'label': '1080p',
            'height': 1080,
            'kind': 'video',
            'ext': 'mp4',
            'sizeBytes': 1024,
            'needsMerge': true,
          },
          {
            'id': 'v2',
            'label': '720p',
            'height': 720,
            'kind': 'video',
            'ext': 'mp4',
          },
          {'id': 'a1', 'label': '오디오', 'kind': 'audio', 'ext': 'm4a'},
        ],
      },
    ],
  };

  setUp(() {
    calls.clear();
    snapshot['folders'] = [];
    snapshot['files'] = [];
    snapshot['jobs'] = [];
    snapshot['recentLinks'] = [];
    snapshot['cookiePlatforms'] = [];
    snapshot['loginStates'] = [];
    snapshot['storageMigration'] = {};
    snapshot['settings'] = {
      'rootUri': 'content://test/root',
      'rootName': 'Fetchy',
      'onboarded': true,
    };
    shared = '';
    failAnalyze = false;
    failEnsureRoot = false;
    snapshotGate = null;
    analysisGate = null;
    previewGate = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(NativeBridge.methods, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'snapshot':
              await snapshotGate?.future;
              return snapshot;
            case 'initialShare':
              return shared.isEmpty ? null : shared;
            case 'ensureRoot':
              if (failEnsureRoot) {
                throw PlatformException(
                  code: 'storage',
                  message: '저장소를 사용할 수 없습니다.',
                );
              }
              snapshot['settings'] = {
                ...asMap(snapshot['settings']),
                'rootUri': 'content://com.luverse.fetchy.documents/tree/fetchy',
                'rootName': 'Fetchy',
              };
              return null;
            case 'folderCreate':
              return {'id': 'created-folder'};
            case 'analyze':
              if (analysisGate != null) return analysisGate!.future;
              if (failAnalyze) {
                throw PlatformException(
                  code: 'analysis',
                  message: '지원하지 않는 링크입니다.',
                );
              }
              return analysis;
            case 'preview':
              return previewGate?.future;
            default:
              return null;
          }
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('com.luverse.fetchy/events'),
          (_) async => null,
        );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(NativeBridge.methods, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('com.luverse.fetchy/events'),
          null,
        );
  });

  Future<void> start(WidgetTester tester) async {
    await tester.binding.setSurfaceSize(const Size(400, 1000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(const FetchyApp());
    await tester.pumpAndSettle();
  }

  test('native map conversion and byte labels', () {
    expect(asMap({1: 'value'}), {'1': 'value'});
    expect(
      asMaps([
        {'id': 1},
        null,
      ]).length,
      1,
    );
    expect(bytesLabel(1024), '1.0 KB');
    expect(bytesLabel(null), '크기 미정');
  });

  testWidgets('shared metadata appears before formats and cannot enqueue', (
    tester,
  ) async {
    shared = 'https://example.com/video';
    analysisGate = Completer<dynamic>();
    previewGate = Completer<dynamic>();
    await tester.pumpWidget(const FetchyApp());
    await tester.pump();
    previewGate!.complete({
      'sourceUrl': shared,
      'title': '빠른 미리보기',
      'platform': 'YouTube',
    });
    await tester.pump();
    await tester.pump();
    expect(find.text('빠른 미리보기'), findsOneWidget);
    expect(find.text('다운로드 가능한 화질을 확인하고 있어요.'), findsOneWidget);
    expect(find.text('저장하기'), findsNothing);
    expect(calls.where((c) => c.method == 'enqueue'), isEmpty);
    analysisGate!.complete(analysis);
    await tester.pumpAndSettle();
    expect(find.text('여행 기록'), findsOneWidget);
    expect(find.text('저장하기'), findsOneWidget);
  });

  testWidgets('ten second delay stops spinner and keeps retry visible', (
    tester,
  ) async {
    shared = 'https://example.com/video';
    analysisGate = Completer<dynamic>();
    previewGate = Completer<dynamic>();
    await tester.pumpWidget(const FetchyApp());
    await tester.pump();
    previewGate!.complete({
      'sourceUrl': shared,
      'title': '미리보기 유지',
      'platform': 'YouTube',
    });
    await tester.pump();
    await tester.pump(const Duration(seconds: 10));
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.text('다시 시도'), findsOneWidget);
    expect(find.text('미리보기 유지'), findsOneWidget);
    analysisGate!.complete(analysis);
    await tester.pumpAndSettle();
    expect(find.text('저장하기'), findsOneWidget);
  });

  testWidgets('late preview cannot replace a completed format selection', (
    tester,
  ) async {
    shared = 'https://example.com/video';
    previewGate = Completer<dynamic>();
    await start(tester);
    previewGate!.complete({'title': '늦은 미리보기', 'platform': 'YouTube'});
    await tester.pumpAndSettle();
    expect(find.text('늦은 미리보기'), findsNothing);
    expect(find.text('여행 기록'), findsOneWidget);
  });

  testWidgets('leaving analysis ignores delayed responses', (tester) async {
    await start(tester);
    analysisGate = Completer<dynamic>();
    previewGate = Completer<dynamic>();
    await tester.enterText(find.byType(TextField), 'https://example.com/video');
    await tester.pump();
    await tester.tap(find.text('링크 분석'));
    await tester.pump();
    await tester.tap(find.byTooltip('뒤로'));
    await tester.pump();
    analysisGate!.complete(analysis);
    previewGate!.complete({'title': '이전 요청'});
    await tester.pumpAndSettle();
    expect(calls.any((c) => c.method == 'cancelAnalysis'), isTrue);
    expect(find.text('여행 기록'), findsNothing);
    expect(find.text('이전 요청'), findsNothing);
    expect(find.text('링크 분석'), findsOneWidget);
  });

  testWidgets(
    'same quality defaults to resolved HTTP and manual quality still wins',
    (tester) async {
      final original = analysis['assets'];
      addTearDown(() => analysis['assets'] = original!);
      analysis['assets'] = [
        {
          'id': 'asset',
          'title': '바다',
          'kind': 'video',
          'formats': [
            {
              'id': 'manifest',
              'label': 'HLS',
              'height': 1080,
              'kind': 'video',
              'ext': 'mp4',
            },
            {
              'id': 'resolved',
              'label': 'HTTPS',
              'height': 1080,
              'kind': 'video',
              'ext': 'mkv',
              'directSources': [],
            },
          ],
        },
      ];
      shared = 'https://example.com/video';
      await start(tester);
      await tester.tap(find.text('저장하기'));
      await tester.pumpAndSettle();
      final first = asMap(
        calls.firstWhere((c) => c.method == 'enqueue').arguments,
      );
      expect(asMaps(first['selections']).single['formatId'], 'resolved');
      // A manually selected manifest remains a supported exact choice.
      await tester.pumpWidget(const SizedBox());
      await start(tester);
      await tester.tap(find.text('화질'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('HLS · mp4'));
      await tester.tap(find.text('HLS · mp4'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('적용'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('저장하기'));
      await tester.pumpAndSettle();
      final last = asMap(
        calls.lastWhere((c) => c.method == 'enqueue').arguments,
      );
      expect(asMaps(last['selections']).single['formatId'], 'manifest');
    },
  );

  testWidgets('quality is selected without an additional format search', (
    tester,
  ) async {
    analysis['canExpandFormats'] = true;
    addTearDown(() => analysis.remove('canExpandFormats'));
    shared = 'https://example.com/video';
    await start(tester);
    expect(find.text('추가 화질 검색'), findsNothing);
    expect(calls.where((c) => c.method == 'analyze'), hasLength(1));
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    expect(
      asMaps(
        asMap(
          calls.singleWhere((c) => c.method == 'enqueue').arguments,
        )['selections'],
      ).single['formatId'],
      'v1',
    );
  });

  testWidgets('Korean tabs and settings render', (tester) async {
    await start(tester);
    expect(find.text('Fetchy'), findsOneWidget);
    await tester.tap(find.text('파일').last);
    await tester.pumpAndSettle();
    expect(find.text('전체 (0)'), findsOneWidget);
    await tester.tap(find.text('설정').last);
    await tester.pumpAndSettle();
    expect(find.text('다운로드 설정'), findsOneWidget);
    expect(find.text('기본 영상 화질'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('URL validation and native error are clear', (tester) async {
    await start(tester);
    await tester.enterText(find.byType(TextField), 'invalid');
    await tester.pump();
    await tester.tap(find.text('링크 분석'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'analyze'), isEmpty);
    expect(find.textContaining('http 또는 https'), findsOneWidget);
    failAnalyze = true;
    await tester.enterText(find.byType(TextField), 'https://example.com/video');
    await tester.pump();
    await tester.tap(find.text('링크 분석'));
    await tester.pumpAndSettle();
    expect(find.text('지원하지 않는 링크입니다.'), findsOneWidget);
  });

  testWidgets('audio tab sends only audio in the enqueue contract', (
    tester,
  ) async {
    await start(tester);
    await tester.enterText(find.byType(TextField), 'https://example.com/video');
    await tester.pump();
    await tester.tap(find.text('링크 분석'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('1080p'));
    await tester.tap(find.text('1080p'));
    await tester.pump();
    await tester.ensureVisible(find.byType(SegmentedButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('오디오').first);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('오디오 · m4a'));
    await tester.tap(find.text('오디오 · m4a'));
    await tester.pump();
    expect(find.textContaining('총 2개 선택'), findsNothing);
    expect(find.text('저장하기 (2개)'), findsNothing);
    await tester.ensureVisible(find.text('저장하기'));
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    final args = asMap(
      calls.singleWhere((c) => c.method == 'enqueue').arguments,
    );
    expect(args['analysis'], analysis);
    expect(asMaps(args['selections']), [
      {'assetId': 'asset', 'formatId': 'a1', 'kind': 'audio', 'ext': 'm4a'},
    ]);
    expect(find.text('다운로드 대기열에 추가했어요.'), findsOneWidget);
  });

  testWidgets('switching back to video keeps its quality and excludes audio', (
    tester,
  ) async {
    shared = 'https://example.com/video';
    await start(tester);
    await tester.tap(find.text('화질'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('720p · mp4'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('적용'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('오디오').first);
    await tester.pumpAndSettle();
    expect(find.textContaining('총 2개 선택'), findsNothing);
    await tester.tap(find.text('동영상').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    expect(
      asMaps(
        asMap(
          calls.singleWhere((c) => c.method == 'enqueue').arguments,
        )['selections'],
      ),
      [
        {'assetId': 'asset', 'formatId': 'v2', 'kind': 'video', 'ext': 'mp4'},
      ],
    );
  });

  testWidgets('first save creates automatic storage without a picker', (
    tester,
  ) async {
    snapshot['settings'] = {};
    await start(tester);
    await tester.enterText(
      find.byType(TextField).first,
      'https://example.com/video',
    );
    await tester.pump();
    await tester.tap(find.text('링크 분석'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('저장하기'));
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'chooseRoot'), isEmpty);
    expect(calls.where((c) => c.method == 'ensureRoot').length, 1);
    expect(calls.where((c) => c.method == 'enqueue').length, 1);
    expect(
      calls.indexWhere((c) => c.method == 'ensureRoot'),
      lessThan(calls.indexWhere((c) => c.method == 'enqueue')),
    );
  });

  testWidgets('automatic storage failure preserves media selection', (
    tester,
  ) async {
    snapshot['settings'] = {};
    failEnsureRoot = true;
    await start(tester);
    await tester.enterText(
      find.byType(TextField).first,
      'https://example.com/video',
    );
    await tester.pump();
    await tester.tap(find.text('링크 분석'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('저장하기'));
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'chooseRoot'), isEmpty);
    expect(calls.where((c) => c.method == 'ensureRoot').length, 1);
    expect(calls.where((c) => c.method == 'enqueue'), isEmpty);
    expect(find.text('저장하기'), findsOneWidget);
  });

  testWidgets(
    'plus creates automatic storage and settings can change location',
    (tester) async {
      snapshot['settings'] = {'onboarded': true};
      await start(tester);
      await tester.tap(find.text('파일').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('새 폴더'));
      await tester.pumpAndSettle();
      expect(calls.where((c) => c.method == 'ensureRoot'), hasLength(1));
      expect(calls.where((c) => c.method == 'chooseRoot'), isEmpty);
      await tester.enterText(find.byType(TextField), '여행');
      await tester.pump();
      await tester.tap(find.text('만들기'));
      await tester.pumpAndSettle();
      expect(calls.where((c) => c.method == 'folderCreate'), hasLength(1));
      await tester.tap(find.text('설정').last);
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('저장 위치'));
      await tester.tap(find.text('저장 위치'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('새 다운로드부터 적용'));
      await tester.pumpAndSettle();
      expect(calls.where((c) => c.method == 'chooseRoot'), hasLength(1));
    },
  );

  testWidgets('missing file can still be deleted from the long press menu', (
    tester,
  ) async {
    snapshot['files'] = [
      {'id': 'gone', 'name': 'gone.mp4', 'mime': 'video/mp4', 'missing': true},
    ];
    await start(tester);
    await tester.tap(find.text('파일').last);
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byWidgetPredicate(
        (widget) =>
            widget is Semantics && widget.properties.label == 'gone.mp4',
      ),
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('삭제'));
    await tester.tap(find.text('삭제'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('삭제').last);
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'fileDelete').arguments, {
      'id': 'gone',
    });
  });

  testWidgets('event snapshot updates job list', (tester) async {
    await start(tester);
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
          'com.luverse.fetchy/events',
          const StandardMethodCodec().encodeSuccessEnvelope({
            ...snapshot,
            'jobs': [
              {
                'id': 'j1',
                'title': '새 작업',
                'state': 'paused',
                'progress': -1,
                'bytes': 0,
              },
            ],
          }),
          (_) {},
        );
    await tester.pumpAndSettle();
    expect(find.text('새 작업'), findsOneWidget);
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('계속 다운로드'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'command').arguments, {
      'id': 'j1',
      'action': 'resume',
    });
  });

  testWidgets('share entry is transparent and closes through native', (
    tester,
  ) async {
    shared = 'https://example.com/video';
    await start(tester);
    expect(find.text('Fetchy에 저장하기'), findsOneWidget);
    expect(
      tester.widget<Scaffold>(find.byType(Scaffold)).backgroundColor,
      Colors.transparent,
    );
    await tester.tap(find.byTooltip('닫기'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'finishShare').length, 1);
    expect(calls.where((c) => c.method == 'analyze').length, 1);
  });
  testWidgets('native percent progress and string quality normalize', (
    tester,
  ) async {
    await start(tester);
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
          'com.luverse.fetchy/events',
          const StandardMethodCodec().encodeSuccessEnvelope({
            ...snapshot,
            'settings': {'quality': '1080'},
            'jobs': [
              {
                'id': 'j',
                'title': '진행 작업',
                'state': 'downloading',
                'progress': 42,
              },
            ],
          }),
          (_) {},
        );
    await tester.pump();
    expect(
      tester
          .widget<LinearProgressIndicator>(find.byType(LinearProgressIndicator))
          .value,
      .42,
    );
    await tester.tap(find.text('설정').last);
    await tester.pumpAndSettle();
    expect(find.text('1080p'), findsOneWidget);
    await tester.tap(find.text('1080p'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('720p').last);
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'settingsSet').arguments, {
      'key': 'quality',
      'value': 720,
    });
  });

  testWidgets('folder creation and default file move match native contract', (
    tester,
  ) async {
    await start(tester);
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
          'com.luverse.fetchy/events',
          const StandardMethodCodec().encodeSuccessEnvelope({
            ...snapshot,
            'folders': [
              {'id': 'folder', 'name': '영상'},
            ],
            'files': [
              {
                'id': 'file',
                'name': 'clip.mp4',
                'mime': 'video/mp4',
                'size': 2048,
                'folderId': 'folder',
              },
            ],
          }),
          (_) {},
        );
    await tester.pumpAndSettle();
    await tester.tap(find.text('파일').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('새 폴더'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '여행');
    await tester.pump();
    await tester.tap(find.text('만들기'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'folderCreate').arguments, {
      'name': '여행',
      'color': 0xffec49b4,
    });
    // Refresh after creation returns the mocked empty snapshot; republish a file.
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
          'com.luverse.fetchy/events',
          const StandardMethodCodec().encodeSuccessEnvelope({
            ...snapshot,
            'files': [
              {'id': 'file', 'name': 'clip.mp4', 'mime': 'video/mp4'},
            ],
          }),
          (_) {},
        );
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byWidgetPredicate(
        (widget) =>
            widget is Semantics && widget.properties.label == 'clip.mp4',
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('이동'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('기본 저장 위치'));
    await tester.tap(find.text('적용'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'fileMove').arguments, {
      'id': 'file',
    });
  });
  testWidgets(
    'folder picker keeps reference order, trailing radios and a visible create action',
    (tester) async {
      snapshot['folders'] = [
        {'id': 'ig', 'name': 'Instagram', 'platform': 'instagram'},
        {'id': 'yt', 'name': 'YouTube', 'platform': 'youtube'},
        for (var index = 0; index < 20; index++)
          {'id': 'custom-$index', 'name': '폴더 $index'},
      ];
      await start(tester);
      await tester.tap(find.text('파일').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('새 폴더'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('기본 저장 위치'));
      await tester.pumpAndSettle();
      final radios = tester
          .widgetList<RadioListTile<String>>(find.byType(RadioListTile<String>))
          .toList();
      expect(radios.map((tile) => tile.value).take(3), ['', 'yt', 'ig']);
      expect(
        radios.every(
          (tile) => tile.controlAffinity == ListTileControlAffinity.trailing,
        ),
        isTrue,
      );
      expect(find.text('새 폴더').hitTestable(), findsOneWidget);
      expect(find.text('적용').hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('share analyzes before snapshot but keeps saved quality', (
    tester,
  ) async {
    shared = 'https://example.com/video';
    snapshot['settings'] = {...asMap(snapshot['settings']), 'quality': 720};
    snapshotGate = Completer<void>();
    await tester.binding.setSurfaceSize(const Size(400, 1000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(const FetchyApp());
    await tester.pump(const Duration(milliseconds: 100));
    expect(calls.any((c) => c.method == 'analyze'), isTrue);
    snapshotGate!.complete();
    await tester.pumpAndSettle();
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    final choices = asMaps(
      asMap(
        calls.singleWhere((c) => c.method == 'enqueue').arguments,
      )['selections'],
    );
    expect(choices.single['formatId'], 'v2');
  });

  testWidgets('X extractor label uses the existing X platform folder', (
    tester,
  ) async {
    final original = analysis['platform'];
    addTearDown(() => analysis['platform'] = original!);
    analysis['platform'] = 'X (Twitter)';
    snapshot['folders'] = [
      {'id': 'x-folder', 'name': '내 X 폴더', 'platform': 'X'},
    ];
    shared = 'https://example.com/video';
    await start(tester);
    expect(find.text('내 X 폴더'), findsOneWidget);
    expect(find.text('기본 저장 위치 / 자동 분류'), findsNothing);
  });

  testWidgets('shared audio selection never enqueues the hidden video format', (
    tester,
  ) async {
    shared = 'https://example.com/video';
    await start(tester);
    await tester.tap(find.text('오디오').first);
    await tester.pumpAndSettle();
    expect(find.textContaining('총 2개 선택'), findsNothing);
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    expect(
      asMaps(
        asMap(
          calls.singleWhere((c) => c.method == 'enqueue').arguments,
        )['selections'],
      ),
      [
        {'assetId': 'asset', 'formatId': 'a1', 'kind': 'audio', 'ext': 'm4a'},
      ],
    );
    expect(calls.where((c) => c.method == 'finishShare'), hasLength(1));
  });

  testWidgets(
    'multiple images remain selectable within the current media tab',
    (tester) async {
      final original = analysis['assets'];
      addTearDown(() => analysis['assets'] = original!);
      analysis['assets'] = [
        for (var index = 0; index < 2; index++)
          {
            'id': 'image-$index',
            'title': '이미지 $index',
            'kind': 'image',
            'formats': [
              {
                'id': 'original-$index',
                'label': '원본',
                'kind': 'image',
                'ext': 'jpg',
              },
            ],
          },
      ];
      shared = 'https://example.com/video';
      await start(tester);
      await tester.tap(find.text('저장하기 (2개)'));
      await tester.pumpAndSettle();
      final selections = asMaps(
        asMap(
          calls.singleWhere((c) => c.method == 'enqueue').arguments,
        )['selections'],
      );
      expect(selections, hasLength(2));
      expect(
        selections.every((selection) => selection['kind'] == 'image'),
        isTrue,
      );
    },
  );

  testWidgets('queued jobs show their network reason and provide restart', (
    tester,
  ) async {
    snapshot['jobs'] = [
      {
        'id': 'wait',
        'title': '대기 검증',
        'state': 'queued',
        'progress': 0,
        'queueReason': 'Wi-Fi 연결을 기다리는 중',
      },
    ];
    await start(tester);
    await tester.ensureVisible(find.textContaining('Wi-Fi 연결을 기다리는 중'));
    expect(find.textContaining('Wi-Fi 연결을 기다리는 중'), findsOneWidget);
    await tester.tap(find.byTooltip('다운로드 관리'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('다시 시작'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((call) => call.method == 'command').arguments, {
      'id': 'wait',
      'action': 'resume',
    });
  });

  testWidgets('cookie login import and deletion call native methods', (
    tester,
  ) async {
    await start(tester);
    await tester.tap(find.text('설정').last);
    await tester.pumpAndSettle();
    for (final entry in {
      '브라우저로 로그인': 'cookiesLogin',
      '쿠키 파일 가져오기': 'cookiesImport',
      '쿠키 삭제': 'cookiesDelete',
    }.entries) {
      await tester.scrollUntilVisible(find.text('youtube'), 400);
      await tester.pumpAndSettle();
      await tester.tap(find.text('youtube'));
      await tester.pumpAndSettle();
      await tester.tap(find.text(entry.key));
      await tester.pumpAndSettle();
      if (entry.value == 'cookiesDelete') {
        await tester.tap(find.text('삭제'));
        await tester.pumpAndSettle();
      }
      expect(calls.singleWhere((c) => c.method == entry.value).arguments, {
        'platform': 'youtube',
      });
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'new platform shortcuts and native cookie registry are reachable',
    (tester) async {
      snapshot['cookiePlatforms'] = ['youtube', 'naver', 'bilibili'];
      await start(tester);
      await tester.tap(find.text('더보기'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Streamable'),
        300,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.tap(find.text('Streamable'));
      await tester.pumpAndSettle();
      expect(calls.singleWhere((c) => c.method == 'openUrl').arguments, {
        'url': 'https://streamable.com/',
      });
      await tester.tap(find.text('설정').last);
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(find.text('naver'), 400);
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('naver'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('naver'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('쿠키 파일 가져오기'));
      await tester.pumpAndSettle();
      expect(calls.singleWhere((c) => c.method == 'cookiesImport').arguments, {
        'platform': 'naver',
      });
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('photo post music downloads only from the selected audio tab', (
    tester,
  ) async {
    final original = analysis['assets'];
    addTearDown(() => analysis['assets'] = original!);
    analysis['assets'] = [
      {
        'id': 'photo',
        'title': '사진',
        'kind': 'image',
        'formats': [
          {'id': 'fetchy-image', 'kind': 'image', 'ext': 'jpg'},
        ],
      },
      {
        'id': 'music',
        'title': '음악',
        'kind': 'audio',
        'formats': [
          {'id': 'photo-audio', 'kind': 'audio', 'ext': 'mp3'},
        ],
      },
    ];
    shared = 'https://www.tiktok.com/@fixture/photo/123';
    await start(tester);
    await tester.tap(find.text('오디오').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    final selections = asMaps(
      asMap(
        calls.singleWhere((c) => c.method == 'enqueue').arguments,
      )['selections'],
    );
    expect(selections, [
      {
        'assetId': 'music',
        'formatId': 'photo-audio',
        'kind': 'audio',
        'ext': 'mp3',
      },
    ]);
  });
  testWidgets('welcome is persisted only after starting', (tester) async {
    snapshot['settings'] = {'onboarded': false};
    await start(tester);
    expect(find.text('시작하기'), findsOneWidget);
    await tester.tap(find.text('시작하기'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'settingsSet').arguments, {
      'key': 'onboarded',
      'value': true,
    });
  });

  testWidgets('plus inside a folder creates a real child with selected color', (
    tester,
  ) async {
    snapshot['folders'] = [
      {
        'id': 'parent',
        'name': 'Instagram',
        'parentId': null,
        'color': 0xffec49b4,
      },
    ];
    await start(tester);
    await tester.tap(find.text('파일').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Instagram'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('새 폴더'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '여행');
    await tester.pump();
    await tester.tap(find.text('만들기'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'folderCreate').arguments, {
      'name': '여행',
      'parentId': 'parent',
      'color': 0xffec49b4,
    });
  });

  testWidgets('favorite filtering and folder menu use stable IDs', (
    tester,
  ) async {
    snapshot['jobs'] = [
      {
        'id': 'pending',
        'title': 'unstarred job',
        'state': 'downloading',
        'progress': 42,
      },
    ];
    snapshot['folders'] = [
      {'id': 'a', 'name': 'Instagram', 'parentId': null, 'favorite': true},
      {'id': 'b', 'name': 'YouTube', 'parentId': null},
    ];
    await start(tester);
    await tester.tap(find.text('파일').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('즐겨찾기'));
    await tester.pumpAndSettle();
    expect(find.text('Instagram'), findsOneWidget);
    expect(find.text('YouTube'), findsNothing);
    expect(find.text('42%'), findsNothing);
    await tester.tap(find.byTooltip('Instagram 폴더 관리'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('즐겨찾기 해제'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'favorite').arguments, {
      'kind': 'folders',
      'id': 'a',
      'value': false,
    });
  });

  testWidgets('recent link reanalysis keeps original source URL', (
    tester,
  ) async {
    snapshot['recentLinks'] = [
      {
        'id': 'r',
        'title': '기록된 링크',
        'sourceUrl': 'https://example.com/video',
        'platform': 'youtube',
        'analyzedAt': DateTime.now().millisecondsSinceEpoch,
      },
    ];
    await start(tester);
    await tester.tap(find.text('기록된 링크'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'analyze').arguments, {
      'url': 'https://example.com/video',
    });
  });

  testWidgets('small screen and enlarged Korean type do not overflow', (
    tester,
  ) async {
    tester.platformDispatcher.textScaleFactorTestValue = 1.3;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.binding.setSurfaceSize(const Size(320, 700));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      MediaQuery(
        data: const MediaQueryData(textScaler: TextScaler.linear(1.3)),
        child: const FetchyApp(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('파일').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('설정').last);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });
  testWidgets('list titles have explicit light and dark contrast colors', (
    tester,
  ) async {
    await start(tester);
    final app = tester.widget<MaterialApp>(find.byType(MaterialApp));
    expect(
      app.theme!.listTileTheme.titleTextStyle!.color,
      app.theme!.colorScheme.onSurface,
    );
    expect(
      app.darkTheme!.listTileTheme.titleTextStyle!.color,
      app.darkTheme!.colorScheme.onSurface,
    );
  });
  testWidgets('dark folder captions follow the active surface color', (
    tester,
  ) async {
    snapshot['settings'] = {...asMap(snapshot['settings']), 'theme': 'dark'};
    snapshot['folders'] = [
      {'id': 'dark', 'name': 'Instagram', 'parentId': null},
    ];
    await start(tester);
    await tester.tap(find.text('파일').last);
    await tester.pumpAndSettle();
    final caption = find.text('0개의 파일');
    expect(
      tester.widget<Text>(caption).style!.color,
      Theme.of(tester.element(caption)).colorScheme.onSurface
          .withValues(alpha: .6),
    );
  });
  testWidgets(
    'dark home shortcut background uses the current shell theme on first render',
    (tester) async {
      snapshot['settings'] = {...asMap(snapshot['settings']), 'theme': 'dark'};
      await start(tester);
      final more = find.byIcon(Icons.more_horiz);
      final tile = find
          .ancestor(of: more, matching: find.byType(Container))
          .first;
      final decoration =
          tester.widget<Container>(tile).decoration! as BoxDecoration;
      expect(
        decoration.color,
        Theme.of(tester.element(more)).colorScheme.surfaceContainerLowest,
      );
      expect(decoration.color, isNot(Colors.white));
    },
  );
  testWidgets(
    'a removed current folder returns browsing and plus to the real root',
    (tester) async {
      snapshot['folders'] = [
        {'id': 'gone', 'name': '여행', 'parentId': null},
      ];
      await start(tester);
      await tester.tap(find.text('파일').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('여행'));
      await tester.pumpAndSettle();
      snapshot['folders'] = [];
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .handlePlatformMessage(
            'com.luverse.fetchy/events',
            const StandardMethodCodec().encodeSuccessEnvelope(snapshot),
            (_) {},
          );
      await tester.pumpAndSettle();
      expect(find.text('전체 (0)'), findsOneWidget);
      await tester.tap(find.byTooltip('새 폴더'));
      await tester.pumpAndSettle();
      expect(find.text('기본 저장 위치'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('highest quality selects the largest format immediately', (
    tester,
  ) async {
    snapshot['settings'] = {...asMap(snapshot['settings']), 'quality': 0};
    shared = 'https://example.com/video';
    await start(tester);
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    final request = asMap(
      calls.singleWhere((c) => c.method == 'enqueue').arguments,
    );
    expect(asMaps(request['selections']).single['formatId'], 'v1');
    expect(calls.where((c) => c.method == 'analyze'), hasLength(1));
  });
  testWidgets('automatic original does not need another preview request', (
    tester,
  ) async {
    final original = analysis['assets'];
    addTearDown(() => analysis['assets'] = original!);
    analysis['assets'] = [
      {
        'id': 'asset',
        'title': '영상',
        'formats': [
          {
            'id': 'original',
            'kind': 'video',
            'height': 0,
            'ext': 'mp4',
            'label': 'Original',
          },
          {
            'id': 'automatic',
            'kind': 'video',
            'height': 0,
            'ext': 'mp4',
            'automatic': true,
            'label': '설정 화질 자동 선택',
          },
        ],
      },
    ];
    shared = 'https://example.com/video';
    await start(tester);
    expect(find.text('추가 화질 검색'), findsNothing);
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    expect(
      asMaps(
        asMap(
          calls.singleWhere((c) => c.method == 'enqueue').arguments,
        )['selections'],
      ).single['formatId'],
      'automatic',
    );
    expect(calls.where((c) => c.method == 'analyze'), hasLength(1));
  });
  testWidgets('subtitle language and format are sent only when selected', (
    tester,
  ) async {
    final asset = asMaps(analysis['assets']).first;
    analysis['assets'] = [
      {
        ...asset,
        'subtitles': [
          {'language': 'ko', 'name': '한국어', 'automatic': false},
          {'language': 'en', 'name': 'English', 'automatic': true},
        ],
      },
    ];
    addTearDown(() => analysis['assets'] = [asset]);
    shared = 'https://example.com/video';
    await start(tester);
    await tester.ensureVisible(find.text('자막'));
    await tester.tap(find.text('자막'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('한국어'));
    await tester.pump();
    await tester.tap(find.text('VTT'));
    await tester.pump();
    await tester.tap(find.text('적용'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('저장하기'));
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    expect(
      asMap(
        calls.singleWhere((c) => c.method == 'enqueue').arguments,
      )['subtitle'],
      {'language': 'ko', 'automatic': false, 'format': 'vtt'},
    );
  });
  testWidgets('audio tags remain editable and video selection stays excluded', (
    tester,
  ) async {
    shared = 'https://example.com/video';
    await start(tester);
    await tester.tap(find.text('오디오').first);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('오디오 정보'));
    await tester.tap(find.text('오디오 정보'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(1), '아티스트');
    await tester.pump();
    await tester.ensureVisible(find.text('적용'));
    await tester.tap(find.text('적용'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('저장하기'));
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    final request = asMap(
      calls.singleWhere((c) => c.method == 'enqueue').arguments,
    );
    expect(asMap(request['audioTags'])['artist'], '아티스트');
    expect(asMaps(request['selections']).single['kind'], 'audio');
  });
  testWidgets('login status check and diagnostics are available in settings', (
    tester,
  ) async {
    snapshot['loginStates'] = [
      {
        'platform': 'youtube',
        'state': 'expired',
        'message': '쿠키 만료 · 다시 로그인하세요',
      },
    ];
    await start(tester);
    await tester.tap(find.text('설정').last);
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('youtube'), 400);
    await tester.tap(find.text('youtube'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('로그인 상태 확인'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'cookiesCheck').arguments, {
      'platform': 'youtube',
    });
    await tester.scrollUntilVisible(find.text('문제 진단 공유'), 300);
    await tester.tap(find.text('문제 진단 공유'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'diagnosticsShare'), hasLength(1));
  });
  testWidgets('storage migration selection and retry use native commands', (
    tester,
  ) async {
    snapshot['storageMigration'] = {
      'state': 'failed',
      'done': 1,
      'total': 3,
      'destinationName': '새 위치',
      'error': '권한 필요',
    };
    await start(tester);
    await tester.tap(find.text('설정').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('이전 계속하기'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'migrationRetry'), hasLength(1));
    await tester.ensureVisible(find.text('저장 위치'));
    await tester.tap(find.text('저장 위치'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('기존 파일도 이전'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'chooseRoot').arguments, {
      'migrate': true,
    });
  });
  testWidgets('file menu opens local player and preserves external open', (
    tester,
  ) async {
    snapshot['files'] = [
      {'id': 'local', 'name': 'local.mp4', 'mime': 'video/mp4'},
    ];
    await start(tester);
    await tester.tap(find.text('파일').last);
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byWidgetPredicate(
        (w) => w is Semantics && w.properties.label == 'local.mp4',
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('열기'), findsOneWidget);
    await tester.tap(find.text('앱에서 재생'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'filePlay').arguments, {
      'id': 'local',
    });
  });
  testWidgets('portrait video detail uses the shorter resolution axis', (
    tester,
  ) async {
    snapshot['files'] = [
      {
        'id': 'portrait',
        'name': 'portrait.mp4',
        'mime': 'video/mp4',
        'width': 1080,
        'height': 1920,
        'fps': 30,
      },
    ];
    await start(tester);
    await tester.tap(find.text('파일').last);
    await tester.pumpAndSettle();
    await tester.tap(
      find.byWidgetPredicate(
        (w) => w is Semantics && w.properties.label == 'portrait.mp4',
      ),
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('1080p 30fps'));
    expect(find.text('1080p 30fps'), findsOneWidget);
    expect(find.text('1920p 30fps'), findsNothing);
  });
  testWidgets('completed migration retains missing item summary', (
    tester,
  ) async {
    snapshot['settings'] = {
      ...asMap(snapshot['settings']),
      'lastMigration': {
        'state': 'completed',
        'destinationName': '새 위치',
        'missing': 2,
      },
    };
    await start(tester);
    await tester.tap(find.text('설정').last);
    await tester.pumpAndSettle();
    expect(find.text('이전 완료 · 새 위치'), findsOneWidget);
    expect(find.text('찾을 수 없는 2개 항목은 기존 위치의 기록으로 유지했습니다.'), findsOneWidget);
  });
  testWidgets('file date setting offers platform date or current save time', (
    tester,
  ) async {
    snapshot['settings'] = {
      ...asMap(snapshot['settings']),
      'fileDate': 'download',
    };
    await start(tester);
    await tester.tap(find.text('설정').last);
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('파일 날짜'), 300);
    await tester.tap(find.text('파일 날짜'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('플랫폼 게시 시각'));
    await tester.pumpAndSettle();
    expect(calls.singleWhere((c) => c.method == 'settingsSet').arguments, {
      'key': 'fileDate',
      'value': 'source',
    });
  });
}
