part of '../main.dart';

extension _FetchyDownloadExtras on _FetchyAppState {
  String automaticLabel() => settings['quality'] == 0
      ? '최고 화질 · 자동'
      : '${settings['quality']}p 기준 · 자동';

  List<Map<String, dynamic>> get subtitleOptions {
    final assets = asMaps(analysis?['assets'])
        .where(
          (a) => formatsFor(
            a,
            kind,
          ).any((f) => selected.contains(selectionKey(a['id'], f['id']))),
        )
        .toList();
    if (assets.isEmpty || kind == 'image') return [];
    return asMaps(assets.first['subtitles'])
        .where(
          (option) => assets.every(
            (asset) => asMaps(asset['subtitles']).any(
              (s) =>
                  s['language'] == option['language'] &&
                  s['automatic'] == option['automatic'],
            ),
          ),
        )
        .toList();
  }

  Future<void> subtitleSheet() async {
    final options = subtitleOptions;
    var value = subtitle == null
        ? ''
        : '${subtitle!['language']}|${subtitle!['automatic']}';
    var format = subtitle?['format']?.toString() ?? 'srt';
    final result = await showModalBottomSheet<Map<String, dynamic>>(
      context: pageContext,
      isScrollControlled: true,
      builder: (context) => StatefulBuilder(
        builder: (context, update) => SafeArea(
          child: SizedBox(
            height: MediaQuery.sizeOf(context).height * .72,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
              child: Column(
                children: [
                  const Text(
                    '자막 저장',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 16),
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(value: 'srt', label: Text('SRT')),
                      ButtonSegment(value: 'vtt', label: Text('VTT')),
                    ],
                    selected: {format},
                    onSelectionChanged: (v) => update(() => format = v.first),
                  ),
                  Expanded(
                    child: RadioGroup<String>(
                      groupValue: value,
                      onChanged: (v) => update(() => value = v ?? ''),
                      child: ListView(
                        children: [
                          const RadioListTile<String>(
                            value: '',
                            title: Text('저장 안 함'),
                          ),
                          for (final option in options)
                            RadioListTile<String>(
                              value:
                                  '${option['language']}|${option['automatic']}',
                              title: Text(
                                '${option['name'] ?? option['language']}',
                              ),
                              subtitle: Text(
                                '${option['language']} · ${option['automatic'] == true ? '자동 생성' : '제공 자막'}',
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                  FilledButton(
                    onPressed: () {
                      final option = options
                          .where(
                            (o) =>
                                '${o['language']}|${o['automatic']}' == value,
                          )
                          .firstOrNull;
                      Navigator.pop(
                        context,
                        option == null
                            ? <String, dynamic>{}
                            : {
                                'language': option['language'],
                                'automatic': option['automatic'] == true,
                                'format': format,
                              },
                      );
                    },
                    child: const Text('적용'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    if (result != null) redraw(() => subtitle = result.isEmpty ? null : result);
  }

  Future<void> audioTagsSheet() async {
    final asset = asMaps(analysis?['assets']).firstOrNull ?? {};
    String clean(dynamic text) =>
        '$text'.replaceAll(RegExp(r'\s+'), ' ').trim();
    final fields = <String, String>{
      'title':
          audioTags?['title'] ??
          clean(asset['track'] ?? asset['title'] ?? analysis?['title'] ?? ''),
      'artist':
          audioTags?['artist'] ??
          clean(asset['artist'] ?? asset['creator'] ?? ''),
      'album': audioTags?['album'] ?? clean(asset['album'] ?? ''),
    };
    final result = await showModalBottomSheet<Map<String, String>>(
      context: pageContext,
      isScrollControlled: true,
      builder: (context) => SafeArea(
        child: SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(
            20,
            0,
            20,
            MediaQuery.viewInsetsOf(context).bottom + 24,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                '오디오 정보',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 14),
              for (final entry in fields.entries)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: TextFormField(
                    initialValue: entry.value,
                    onChanged: (value) => fields[entry.key] = value,
                    maxLength: 240,
                    decoration: InputDecoration(
                      labelText: {
                        'title': '제목',
                        'artist': '아티스트',
                        'album': '앨범',
                      }[entry.key],
                    ),
                  ),
                ),
              FilledButton(
                onPressed: () => Navigator.pop(
                  context,
                  fields.map((key, c) => MapEntry(key, c.trim())),
                ),
                child: const Text('적용'),
              ),
            ],
          ),
        ),
      ),
    );
    if (result != null) redraw(() => audioTags = result);
  }

  Future<void> changeStorageLocation() async {
    final move = await showModalBottomSheet<bool>(
      context: pageContext,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const ListTile(
              title: Text(
                '저장 위치 변경',
                style: TextStyle(fontWeight: FontWeight.w700),
              ),
            ),
            ListTile(
              leading: const Icon(Icons.folder_outlined),
              title: const Text('새 다운로드부터 적용'),
              subtitle: const Text('기존 파일은 현재 위치에 유지합니다.'),
              onTap: () => Navigator.pop(context, false),
            ),
            ListTile(
              leading: const Icon(Icons.drive_file_move_outlined),
              title: const Text('기존 파일도 이전'),
              subtitle: const Text('앱의 파일과 폴더를 검증하며 이동합니다. 같은 이름은 별도로 보존합니다.'),
              onTap: () => Navigator.pop(context, true),
            ),
          ],
        ),
      ),
    );
    if (move != null) await mutate('chooseRoot', {'migrate': move});
  }

  Widget migrationCard() {
    final migration = asMap(snapshot['storageMigration']);
    if (migration.isEmpty) {
      final completed = asMap(settings['lastMigration']);
      if (completed.isEmpty) return const SizedBox.shrink();
      final missing = (completed['missing'] as num?)?.toInt() ?? 0;
      return ListTile(
        leading: const Icon(Icons.check_circle_outline),
        title: Text('이전 완료 · ${completed['destinationName'] ?? ''}'),
        subtitle: Text(
          missing > 0
              ? '찾을 수 없는 $missing개 항목은 기존 위치의 기록으로 유지했습니다.'
              : '파일과 폴더를 새 위치에 보존했습니다.',
        ),
      );
    }
    final state = '${migration['state']}';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '저장 위치 이전 · ${migration['destinationName'] ?? ''}',
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 8),
            Text('${migration['done'] ?? 0} / ${migration['total'] ?? 0}개 처리'),
            if (state == 'failed') ...[
              Text('${migration['error'] ?? '이전을 완료하지 못했습니다.'}'),
              if ('${migration['errorHelp'] ?? ''}'.isNotEmpty)
                Text('${migration['errorHelp']}'),
              TextButton(
                onPressed: () => mutate('migrationRetry'),
                child: const Text('이전 계속하기'),
              ),
            ] else
              const Padding(
                padding: EdgeInsets.only(top: 12),
                child: LinearProgressIndicator(),
              ),
          ],
        ),
      ),
    );
  }
}
