import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'native_bridge.dart';
import 'ui/components.dart';
import 'ui/theme.dart';

part 'ui/screens.dart';
part 'ui/folders.dart';
part 'ui/media.dart';
part 'ui/download_extras.dart';

void main() => runApp(const FetchyApp());

class FetchyApp extends StatefulWidget {
  const FetchyApp({super.key});
  @override
  State<FetchyApp> createState() => _FetchyAppState();
}

class _FetchyAppState extends State<FetchyApp> {
  final bridge = NativeBridge();
  final messenger = GlobalKey<ScaffoldMessengerState>();
  final navigator = GlobalKey<NavigatorState>();
  final url = TextEditingController();
  StreamSubscription<Map<String, dynamic>>? subscription;
  Map<String, dynamic> snapshot = {};
  Map<String, dynamic>? analysis;
  Map<String, dynamic>? preview;
  Timer? analysisWaitTimer;
  int analysisEpoch = 0;
  bool analysisDelayed = false;
  String? analysisError;
  Stopwatch? previewWatch;
  bool previewImageReported = false;
  final Set<String> selected = {};
  int tab = 0;
  String kind = 'video';
  String? outputVideoFormat;
  String? folderId;
  Map<String, dynamic>? subtitle;
  Map<String, String>? audioTags;
  final Set<String> checkingLogins = {};
  String browseFolder = '';
  String fileFilter = 'all';
  String fileSearch = '';
  bool searchVisible = false;
  bool showHistory = false;
  bool settingsFromGear = false;
  bool showRecent = false;
  final Map<String, Map<String, dynamic>> fileDetails = {};
  final Set<String> requestedDetails = {};
  void redraw(VoidCallback action) {
    if (mounted) setState(action);
  }

  bool busy = false;
  bool loading = true;
  bool shareMode = false;
  String? connectionError;

  Map<String, dynamic> get settings => {
    ...defaultSettings,
    ...asMap(snapshot['settings']),
    'quality':
        int.tryParse('${asMap(snapshot['settings'])['quality'] ?? 1080}') ??
        1080,
  };

  String get rootName {
    final name = settings['rootName']?.toString() ?? '';
    return name.isEmpty ? 'Fetchy (자동)' : name;
  }

  List<Map<String, dynamic>> get folders => asMaps(snapshot['folders']);

  @override
  void initState() {
    super.initState();
    shareMode = WidgetsBinding.instance.platformDispatcher.defaultRouteName
        .contains('share');
    subscription = bridge.snapshots.listen(
      applySnapshot,
      onError: (Object e) {
        showError(e);
      },
    );
    initialize();
  }

  Future<void> initialize() async {
    final snapshotReady = refresh();
    final shared = await invoke('initialShare');
    if (!mounted) return;
    final route = WidgetsBinding.instance.platformDispatcher.defaultRouteName;
    if ((shared is String && shared.isNotEmpty) || route.contains('share')) {
      setState(() {
        shareMode = true;
        if (shared is String) url.text = shared;
      });
      if (url.text.isNotEmpty) await analyze(snapshotReady: snapshotReady);
    }
    await snapshotReady;
  }

  void applySnapshot(Map<String, dynamic> value) {
    if (!mounted) return;
    setState(() {
      snapshot = value;
      for (final id in fileDetails.keys.toList()) {
        final current = asMaps(value['files'])
            .where((f) => '${f['id']}' == id)
            .firstOrNull;
        if (current == null || current['uri'] != fileDetails[id]?['uri']) {
          fileDetails.remove(id);
          requestedDetails.remove(id);
        }
      }
      loading = false;
      connectionError = null;
      if (folderId != null && !folders.any((f) => f['id'] == folderId)) {
        folderId = null;
      }
      if (browseFolder.isNotEmpty &&
          !folders.any((f) => f['id'] == browseFolder)) {
        browseFolder = '';
      }
    });
  }

  void showError(Object error) {
    final message = switch (error) {
      PlatformException e =>
        '${e.message ?? '네이티브 작업에 실패했습니다.'}${asMap(e.details)['help'] == null ? '' : '\n${asMap(e.details)['help']}'}',
      MissingPluginException _ => 'Android 엔진에 연결할 수 없습니다. 앱을 다시 실행해 주세요.',
      _ => error.toString(),
    };
    if (!mounted) return;
    messenger.currentState?.hideCurrentSnackBar();
    messenger.currentState?.showSnackBar(
      SnackBar(content: Text(message), behavior: SnackBarBehavior.floating),
    );
  }

  Future<dynamic> invoke(String method, [Map<String, dynamic>? args]) async {
    try {
      return await bridge.call(method, args);
    } catch (e) {
      showError(e);
      return null;
    }
  }

  Future<bool> mutate(String method, [Map<String, dynamic>? args]) async {
    try {
      await bridge.call(method, args);
      await refresh();
      return true;
    } catch (e) {
      showError(e);
      return false;
    }
  }

  Future<void> refresh() async {
    try {
      final result = await bridge.call('snapshot');
      if (result is! Map) throw const FormatException('엔진 응답 형식이 올바르지 않습니다.');
      applySnapshot(asMap(result));
    } catch (e) {
      if (mounted) {
        setState(() {
          loading = false;
          connectionError = '엔진 연결을 확인해 주세요.';
        });
      }
      showError(e);
    }
  }

  Future<void> analyze({
    Future<void>? snapshotReady,
    bool fullFormats = false,
  }) async {
    final input =
        RegExp(r'https?://[^\s<>]+').firstMatch(url.text.trim())?.group(0) ??
        url.text.trim();
    final parsed = Uri.tryParse(input);
    if (parsed == null ||
        !['http', 'https'].contains(parsed.scheme) ||
        parsed.host.isEmpty) {
      showError(const FormatException('http 또는 https 링크를 입력해 주세요.'));
      return;
    }
    final epoch = ++analysisEpoch;
    final watch = Stopwatch()..start();
    previewWatch = watch;
    previewImageReported = false;
    final previous = fullFormats ? analysis : null;
    analysisWaitTimer?.cancel();
    setState(() {
      busy = true;
      analysis = null;
      preview = previous == null
          ? null
          : {
              ...previous,
              'thumbnail': asMaps(previous['assets']).firstOrNull?['thumbnail'],
            };
      analysisDelayed = false;
      analysisError = null;
      selected.clear();
    });
    analysisWaitTimer = Timer(const Duration(seconds: 10), () {
      if (mounted && epoch == analysisEpoch && analysis == null && busy) {
        setState(() => analysisDelayed = true);
      }
    });
    unawaited(() async {
      try {
        final result = await bridge.call('preview', {'url': input});
        if (!mounted ||
            epoch != analysisEpoch ||
            analysis != null ||
            result is! Map) {
          return;
        }
        setState(() => preview = asMap(result));
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted && epoch == analysisEpoch) {
            debugPrint(
              'FetchyPreview frame elapsedMs=${watch.elapsedMilliseconds}',
            );
          }
        });
      } catch (_) {
        /* Preview failure does not prevent the full extractor fallback. */
      }
    }());
    try {
      // Start extraction while the initial file/settings snapshot is still loading.
      // Apply saved quality settings before choosing defaults.
      final results = await Future.wait<dynamic>([
        bridge.call('analyze', {
          'url': input,
          if (fullFormats) 'fullFormats': true,
        }),
        ?snapshotReady,
      ]);
      final result = results.first;
      if (result is! Map) throw const FormatException('분석 결과가 올바르지 않습니다.');
      if (mounted && epoch == analysisEpoch) {
        setState(() {
          analysis = asMap(result);
          subtitle = null;
          audioTags = null;
          outputVideoFormat = settings['videoFormat']?.toString() ?? 'mp4';
          final kinds = asMaps(analysis!['assets'])
              .expand((a) => asMaps(a['formats']))
              .map((f) => f['kind'])
              .toSet();
          if (!kinds.contains(kind) && kinds.isNotEmpty) {
            kind = kinds.first.toString();
          }
          selectDefaults();
        });
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted && epoch == analysisEpoch) {
            debugPrint(
              'FetchySelection frame elapsedMs=${watch.elapsedMilliseconds}',
            );
          }
        });
      }
    } catch (e) {
      if (mounted && epoch == analysisEpoch) {
        setState(() {
          analysisError = e is PlatformException ? e.message : '분석에 실패했습니다.';
          if (previous != null) {
            analysis = previous;
            selectDefaults();
          }
        });
        showError(e);
      }
    } finally {
      if (mounted && epoch == analysisEpoch) {
        analysisWaitTimer?.cancel();
        setState(() {
          busy = false;
        });
      }
    }
  }

  String selectionKey(dynamic asset, dynamic format) => '$asset\u0000$format';

  List<Map<String, dynamic>> formatsFor(
    Map<String, dynamic> asset,
    String mediaKind,
  ) =>
      asMaps(asset['formats'])
          .where((f) => (f['kind'] ?? asset['kind']) == mediaKind)
          .toList();

  Map<String, dynamic>? preferredFormat(
    Map<String, dynamic> asset,
    String mediaKind,
  ) {
    final formats = formatsFor(asset, mediaKind);
    if (mediaKind == 'video') {
      final automatic = formats
          .where((f) => f['automatic'] == true)
          .firstOrNull;
      if (automatic != null) return automatic;
    }
    final quality = settings['quality'] as int;
    int height(Map<String, dynamic> f) => (f['height'] as num?)?.toInt() ?? 0;
    if (mediaKind == 'video' && quality > 0) {
      final eligible = formats.where((f) => height(f) <= quality).toList();
      if (eligible.isNotEmpty) {
        formats.clear();
        formats.addAll(eligible);
      } else if (formats.isNotEmpty) {
        final smallest = formats.map(height).reduce((a, b) => a < b ? a : b);
        formats.removeWhere((f) => height(f) != smallest);
      }
    }
    final preferredExt =
        settings[mediaKind == 'audio' ? 'audioFormat' : 'videoFormat'];
    formats.sort((a, b) {
      if (mediaKind == 'video') {
        final resolution = height(b).compareTo(height(a));
        if (resolution != 0) return resolution;
        // Same resolution: resolved HTTP streams avoid manifest/extractor startup.
        final direct =
            (b.containsKey('directUrl') || b.containsKey('directSources')
                    ? 1
                    : 0)
                .compareTo(
                  a.containsKey('directUrl') || a.containsKey('directSources')
                      ? 1
                      : 0,
                );
        if (direct != 0) return direct;
      }
      final preference = (b['ext'] == preferredExt ? 1 : 0).compareTo(
        a['ext'] == preferredExt ? 1 : 0,
      );
      if (preference != 0) return preference;
      if (mediaKind == 'audio') {
        final direct = (b.containsKey('directUrl') ? 1 : 0).compareTo(
          a.containsKey('directUrl') ? 1 : 0,
        );
        if (direct != 0) return direct;
      }
      // Native order is meaningful when metadata cannot distinguish formats.
      return asMaps(asset['formats'])
          .indexWhere((f) => f['id'] == a['id'])
          .compareTo(
            asMaps(asset['formats']).indexWhere((f) => f['id'] == b['id']),
          );
    });
    return formats.firstOrNull;
  }

  void selectFormat(Map<String, dynamic> asset, String mediaKind, String? key) {
    for (final format in formatsFor(asset, mediaKind)) {
      selected.remove(selectionKey(asset['id'], format['id']));
    }
    if (key != null) selected.add(key);
  }

  void selectDefaults() {
    for (final asset in asMaps(analysis?['assets'])) {
      final formats = formatsFor(asset, kind);
      if (formats.any(
        (f) => selected.contains(selectionKey(asset['id'], f['id'])),
      )) {
        continue;
      }
      final format = preferredFormat(asset, kind);
      if (format != null) {
        selectFormat(asset, kind, selectionKey(asset['id'], format['id']));
      }
    }
  }

  List<Map<String, dynamic>> get selections => [
    for (final asset in asMaps(analysis?['assets']))
      for (final format in formatsFor(asset, kind))
        if (selected.contains(selectionKey(asset['id'], format['id'])))
          {
            'assetId': asset['id'],
            'formatId': format['id'],
            'kind': format['kind'] ?? asset['kind'],
            'ext': format['ext'],
          },
  ];

  Future<void> enqueue() async {
    if (busy || selections.isEmpty) return;
    if ((settings['rootUri']?.toString() ?? '').isEmpty) {
      if (!await mutate('ensureRoot')) return;
      if (!mounted || (settings['rootUri']?.toString() ?? '').isEmpty) return;
    }
    setState(() {
      busy = true;
    });
    final success = await mutate('enqueue', {
      'analysis': analysis,
      'selections': selections,
      'videoFormat': outputVideoFormat ?? settings['videoFormat'],
      if (subtitle != null &&
          kind != 'image' &&
          subtitleOptions.any(
            (s) =>
                s['language'] == subtitle!['language'] &&
                s['automatic'] == subtitle!['automatic'],
          ))
        'subtitle': subtitle,
      if (audioTags != null && kind == 'audio') 'audioTags': audioTags,
      if (folderId != null) 'folderId': folderId,
    });
    if (!mounted) return;
    setState(() {
      busy = false;
      if (success) {
        analysis = null;
        preview = null;
        selected.clear();
        url.clear();
      }
    });
    if (success) {
      messenger.currentState?.showSnackBar(
        const SnackBar(content: Text('다운로드 대기열에 추가했어요.')),
      );
      if (shareMode) await invoke('finishShare');
    }
  }

  @override
  void dispose() {
    analysisWaitTimer?.cancel();
    subscription?.cancel();
    url.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Fetchy',
    initialRoute: '/',
    debugShowCheckedModeBanner: false,
    scaffoldMessengerKey: messenger,
    navigatorKey: navigator,
    theme: fetchyTheme(Brightness.light),
    darkTheme: fetchyTheme(Brightness.dark),
    themeMode: switch (settings['theme']) {
      'light' => ThemeMode.light,
      'dark' => ThemeMode.dark,
      _ => ThemeMode.system,
    },
    home: Builder(builder: appShell),
  );

  Widget heading(String title, String subtitle) => Padding(
    padding: const EdgeInsets.only(bottom: 24),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: const TextStyle(
            fontSize: 26,
            fontWeight: FontWeight.w700,
            height: 1.3,
          ),
        ),
        const SizedBox(height: 8),
        Text(subtitle, style: const TextStyle(fontSize: 14, height: 1.5)),
      ],
    ),
  );

  Widget section(String text) => Padding(
    padding: const EdgeInsets.only(top: 24, bottom: 10),
    child: Text(
      text,
      style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
    ),
  );

  Widget empty(IconData icon, String title, String detail) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 32),
    child: Column(
      children: [
        Icon(icon, size: 42, color: fetchyBlue),
        const SizedBox(height: 12),
        Text(
          title,
          style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16),
        ),
        const SizedBox(height: 6),
        Text(detail, textAlign: TextAlign.center),
      ],
    ),
  );

  String get kindLabel => switch (kind) {
    'audio' => '오디오',
    'image' => '이미지',
    _ => '영상',
  };

  Widget jobTile(Map<String, dynamic> job) {
    final state = job['state']?.toString() ?? 'queued';
    final progress = job['progress'] is num
        ? (job['progress'] as num).toDouble()
        : -1.0;
    final label = state == 'queued' && '${job['queueReason'] ?? ''}'.isNotEmpty
        ? '${job['queueReason']}'
        : {
                'queued': '대기 중',
                'downloading': '다운로드 중',
                'running': '다운로드 중',
                'paused': '일시정지',
                'completed': '완료',
                'failed': '실패',
                'cancelled': '취소됨',
                'canceled': '취소됨',
                'merging': '병합 중',
                'analyzing': '분석 중',
                'converting': '변환 중',
                'verifying': '파일 검증 중',
                'ready': '저장 준비',
                'saving': '저장 중',
              }[state] ??
              state;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.download_rounded),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    '${job['title'] ?? job['fileName'] ?? '다운로드'}',
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
                PopupMenuButton<String>(
                  tooltip: '다운로드 관리',
                  onSelected: (action) async {
                    if (action == 'diagnostics') {
                      await invoke('diagnosticsShare', {'id': job['id']});
                      return;
                    }
                    if (action == 'deleteRecord' &&
                        !await confirm('기록 삭제', '다운로드 기록을 삭제할까요?')) {
                      return;
                    }
                    await mutate('command', {
                      'id': job['id'],
                      'action': action,
                    });
                  },
                  itemBuilder: (_) => [
                    if (state == 'failed')
                      const PopupMenuItem(
                        value: 'diagnostics',
                        child: Text('문제 진단 공유'),
                      ),
                    if ([
                      'queued',
                      'running',
                      'downloading',
                      'analyzing',
                      'merging',
                      'converting',
                      'verifying',
                      'ready',
                      'saving',
                    ].contains(state))
                      const PopupMenuItem(value: 'pause', child: Text('일시정지')),
                    if (state == 'paused')
                      const PopupMenuItem(
                        value: 'resume',
                        child: Text('계속 다운로드'),
                      ),
                    if (state == 'queued')
                      const PopupMenuItem(
                        value: 'resume',
                        child: Text('다시 시작'),
                      ),
                    if (['failed', 'cancelled', 'canceled'].contains(state))
                      const PopupMenuItem(value: 'retry', child: Text('다시 시도')),
                    if (![
                      'completed',
                      'failed',
                      'cancelled',
                      'canceled',
                    ].contains(state))
                      const PopupMenuItem(value: 'cancel', child: Text('취소')),
                    if ([
                      'completed',
                      'failed',
                      'canceled',
                      'cancelled',
                      'paused',
                    ].contains(state))
                      const PopupMenuItem(
                        value: 'deleteRecord',
                        child: Text('기록 삭제'),
                      ),
                  ],
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text('$label · ${job['platform'] ?? ''}'),
            if ([
              'running',
              'downloading',
              'merging',
              'queued',
              'analyzing',
              'converting',
              'verifying',
              'ready',
              'saving',
            ].contains(state))
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: LinearProgressIndicator(
                  value: progress < 0 ? null : (progress / 100).clamp(0, 1),
                  borderRadius: BorderRadius.circular(4),
                ),
              ),
            Text(
              '${bytesLabel(job['bytes'])} / ${bytesLabel(job['totalBytes'])}',
            ),
            if (job['error'] != null && '${job['error']}'.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  '${job['error']}',
                  style: TextStyle(
                    color: Theme.of(messenger.currentContext!)
                        .colorScheme
                        .error,
                  ),
                ),
              ),
            if ('${job['errorHelp'] ?? ''}'.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text('${job['errorHelp']}'),
              ),
            for (final warning in (job['warnings'] as List?) ?? const [])
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text(
                  '$warning',
                  style: const TextStyle(color: Color(0xffb56c00)),
                ),
              ),
          ],
        ),
      ),
    );
  }

  BuildContext get pageContext => navigator.currentState!.overlay!.context;

  Future<String?> askName(String title, [String initial = '']) async {
    final controller = TextEditingController(text: initial);
    final result = await showDialog<String>(
      context: pageContext,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLength: 180,
          decoration: const InputDecoration(labelText: '이름'),
          onSubmitted: (value) {
            if (value.trim().isNotEmpty) Navigator.pop(context, value.trim());
          },
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () {
              if (controller.text.trim().isNotEmpty) {
                Navigator.pop(context, controller.text.trim());
              }
            },
            child: const Text('저장'),
          ),
        ],
      ),
    );
    // Dialog exit animation can still read its controller.
    await Future<void>.delayed(const Duration(milliseconds: 300));
    controller.dispose();
    return result;
  }

  Future<bool> confirm(String title, String detail) async =>
      await showDialog<bool>(
        context: pageContext,
        builder: (context) => AlertDialog(
          title: Text(title),
          content: Text(detail),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('삭제'),
            ),
          ],
        ),
      ) ??
      false;

  Widget toggle(String key, String title, String detail) =>
      SwitchListTile.adaptive(
        contentPadding: const EdgeInsets.symmetric(horizontal: 14),
        title: Text(title),
        subtitle: detail.isEmpty ? null : Text(detail),
        value: settings[key] == true,
        onChanged: (value) =>
            mutate('settingsSet', {'key': key, 'value': value}),
      );

  Widget choice(String key, String title, Map<dynamic, String> options) =>
      ListTile(
        title: Text(title),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              options[settings[key]] ?? '${settings[key] ?? '선택'}',
              style: TextStyle(
                color: Theme.of(pageContext).colorScheme.onSurface
                    .withValues(alpha: .55),
                fontSize: 13,
              ),
            ),
            const Icon(Icons.chevron_right, size: 18),
          ],
        ),
        onTap: () async {
          final value = await showModalBottomSheet<dynamic>(
            context: pageContext,
            builder: (context) => SafeArea(
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    ListTile(
                      title: Text(
                        title,
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ),
                    for (final option in options.entries)
                      ListTile(
                        title: Text(option.value),
                        trailing: option.key == settings[key]
                            ? const Icon(Icons.check_circle, color: fetchyBlue)
                            : null,
                        onTap: () => Navigator.pop(context, option.key),
                      ),
                  ],
                ),
              ),
            ),
          );
          if (value != null) {
            await mutate('settingsSet', {'key': key, 'value': value});
          }
        },
      );

  List<Widget> settingsPage() => [
    section('다운로드 설정'),
    migrationCard(),
    choice('quality', '기본 영상 화질', {
      2160: '2160p',
      1440: '1440p',
      1080: '1080p',
      720: '720p',
      480: '480p',
      360: '360p',
      0: '최고 품질',
    }),
    choice('audioFormat', '기본 오디오 형식', {
      'm4a': 'M4A',
      'mp3': 'MP3',
      'opus': 'Opus',
    }),
    ListTile(
      title: const Text('저장 위치'),
      subtitle:
          '${settings['rootUri'] ?? ''}'.startsWith(
            'content://com.luverse.fetchy.documents/',
          )
          ? const Text('자동 폴더 · 앱 삭제 시 파일도 삭제돼요.')
          : null,
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            rootName,
            style: TextStyle(
              fontSize: 13,
              color: Theme.of(pageContext).colorScheme.onSurface
                  .withValues(alpha: .55),
            ),
          ),
          const Icon(Icons.chevron_right, size: 18),
        ],
      ),
      onTap: changeStorageLocation,
    ),
    toggle(
      'platformFolders',
      '플랫폼별 폴더 자동 생성',
      'YouTube, Instagram 등 플랫폼별로 파일을 정리합니다.',
    ),
    section('네트워크'),
    toggle('wifiOnly', 'Wi-Fi에서만 다운로드', ''),
    choice('concurrency', '동시 다운로드 수', {1: '1개', 2: '2개', 3: '3개', 4: '4개'}),
    SwitchListTile(
      title: const Text('자동 재시도'),
      value: (int.tryParse('${settings['retries']}') ?? 3) > 0,
      onChanged: (value) =>
          mutate('settingsSet', {'key': 'retries', 'value': value ? 3 : 0}),
    ),
    section('파일 저장과 다운로드'),
    choice('videoFormat', '영상 형식', {
      'mp4': 'MP4',
      'webm': 'WebM',
      'mkv': 'MKV',
    }),
    choice('nameRule', '파일 이름 규칙', {
      'title_id': '제목 + ID',
      'title': '제목',
      'date_title': '날짜 + 제목',
    }),
    choice('duplicates', '중복 파일', {
      'rename': '새 이름으로 저장',
      'skip': '건너뛰기',
      'replace': '덮어쓰기',
    }),
    toggle('mobileData', '모바일 데이터 허용', 'Wi-Fi 전용 설정이 켜져 있으면 대기'),
    choice('retries', '재시도 횟수', {
      0: '사용 안 함',
      1: '1회',
      2: '2회',
      3: '3회',
      4: '4회',
      5: '5회',
    }),
    toggle('resume', '이어받기', '가능한 경우 중단된 지점부터 계속'),
    toggle('audioMetadata', '오디오 정보 저장', '제목·아티스트·앨범을 파일에 저장'),
    toggle('audioCover', '오디오 커버 저장', '제공되는 커버를 지원 형식에 포함'),
    section('앱'),
    toggle('notifications', '다운로드 알림', '진행 상황과 완료 알림 표시'),
    choice('theme', '화면 테마', {'system': '시스템', 'light': '라이트', 'dark': '다크'}),
    section('로그인과 쿠키'),
    const Text('로그인이 필요한 콘텐츠에 사용됩니다. 쿠키는 기기에 보관되며 언제든 삭제할 수 있어요.'),
    ...cookiePlatforms.map(cookieTile),
    section('관리'),
    ListTile(
      leading: const Icon(Icons.bug_report_outlined),
      title: const Text('문제 진단 공유'),
      subtitle: const Text('쿠키·계정·원본 링크·제목은 제외합니다.'),
      onTap: () => invoke('diagnosticsShare'),
    ),
    ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 14),
      leading: const Icon(Icons.cleaning_services_outlined),
      title: const Text('임시 파일 정리'),
      subtitle: const Text('사용하지 않는 다운로드 임시 파일 삭제'),
      onTap: () async {
        if (await confirm('임시 파일 정리', '사용하지 않는 임시 파일을 삭제할까요?')) {
          if (await mutate('cleanup')) {
            redraw(() {
              fileDetails.clear();
              requestedDetails.clear();
            });
          }
        }
      },
    ),
    ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 14),
      title: const Text('다운로드 엔진'),
      subtitle: Text(
        asMap(snapshot['engine'])['status'] == 'ready'
            ? '기기 내 처리 · yt-dlp ${asMap(snapshot['engine'])['ytDlp'] ?? ''}\n영상 분석과 변환은 이 기기에서 실행됩니다.'
            : asMap(snapshot['engine'])['status'] == 'unavailable'
            ? '엔진을 준비하지 못했습니다. 앱을 다시 열어주세요.'
            : '기기 내 엔진 준비 중',
      ),
    ),
    ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 14),
      title: const Text('오픈소스 라이선스'),
      trailing: const Icon(Icons.chevron_right),
      onTap: () async {
        final text = await invoke('licenses');
        if (!mounted || text is! String) return;
        await showDialog<void>(
          context: pageContext,
          builder: (context) => AlertDialog(
            title: const Text('오픈소스 라이선스'),
            content: SizedBox(
              width: 600,
              child: SingleChildScrollView(child: SelectableText(text)),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('닫기'),
              ),
            ],
          ),
        );
      },
    ),
    const SizedBox(height: 20),
    const Center(child: Text('fetchy · 필요한 것만, 간편하게.')),
  ];

  Set<String> get cookiePlatforms => {
    'youtube',
    'instagram',
    'tiktok',
    'x',
    ...((snapshot['cookiePlatforms'] as List?) ?? const []).map((p) => '$p'),
    ...asMaps(snapshot['cookies']).map((c) => '${c['platform']}'),
  };

  Widget cookieTile(String platform) {
    final cookies = asMaps(snapshot['cookies'])
        .where((c) => c['platform'] == platform);
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 14),
      leading: const Icon(Icons.lock_outline_rounded),
      title: Text(platform),
      subtitle: Text(
        asMaps(snapshot['loginStates'])
                .where((s) => s['platform'] == platform)
                .firstOrNull?['message']
                ?.toString() ??
            (cookies.isEmpty ? '저장된 쿠키 없음' : '쿠키 저장됨'),
      ),
      trailing: checkingLogins.contains(platform)
          ? const SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : const Icon(Icons.chevron_right),
      onTap: () => cookieDialog(platform),
    );
  }

  Future<void> cookieDialog(String platform) async {
    final action = await showDialog<String>(
      context: pageContext,
      builder: (context) => SimpleDialog(
        title: Text('$platform 쿠키'),
        children: [
          SimpleDialogOption(
            onPressed: () => Navigator.pop(context, 'cookiesCheck'),
            child: const Text('로그인 상태 확인'),
          ),
          SimpleDialogOption(
            onPressed: () => Navigator.pop(context, 'cookiesLogin'),
            child: const Text('브라우저로 로그인'),
          ),
          SimpleDialogOption(
            onPressed: () => Navigator.pop(context, 'cookiesImport'),
            child: const Text('쿠키 파일 가져오기'),
          ),
          SimpleDialogOption(
            onPressed: () => Navigator.pop(context, 'cookiesDelete'),
            child: const Text('쿠키 삭제'),
          ),
        ],
      ),
    );
    if (action == null) return;
    if (action == 'cookiesCheck') {
      if (checkingLogins.contains(platform)) return;
      redraw(() => checkingLogins.add(platform));
      await mutate(action, {'platform': platform});
      redraw(() => checkingLogins.remove(platform));
      return;
    }
    if (action == 'cookiesDelete' &&
        !await confirm('쿠키 삭제', '$platform 로그인 쿠키를 삭제할까요?')) {
      return;
    }
    await mutate(action, {'platform': platform});
  }
}
