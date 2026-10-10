part of '../main.dart';

extension _FetchyMedia on _FetchyAppState {
  Widget fileScroll(BuildContext context) => CustomScrollView(
    slivers: [
      const SliverToBoxAdapter(child: SizedBox(height: 12)),
      for (final widget in fileBrowser(context))
        SliverPadding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          sliver: widget is GridView
              ? SliverGrid(
                  delegate: widget.childrenDelegate,
                  gridDelegate: widget.gridDelegate,
                )
              : SliverToBoxAdapter(child: widget),
        ),
      const SliverToBoxAdapter(child: SizedBox(height: 100)),
    ],
  );
  List<Widget> fileBrowser(BuildContext context) {
    final nested = browseFolder.isNotEmpty;
    bool matches(Map<String, dynamic> item) =>
        fileSearch.isEmpty ||
        '${item['name']}'.toLowerCase().contains(fileSearch.toLowerCase());
    final folderList = folders
        .where(
          (f) => fileFilter == 'favorite'
              ? f['favorite'] == true
              : fileFilter == 'recent'
              ? false
              : (f['parentId']?.toString() ?? '') == browseFolder,
        )
        .where(matches)
        .toList();
    const platformOrder = {
      'youtube': 0,
      'instagram': 1,
      'tiktok': 2,
      'x': 3,
      'reddit': 4,
      'pinterest': 5,
      'facebook': 6,
      'other': 7,
    };
    folderList.sort((a, b) {
      final order = (platformOrder['${a['platform']}'.toLowerCase()] ?? 8)
          .compareTo(platformOrder['${b['platform']}'.toLowerCase()] ?? 8);
      return order != 0 ? order : '${a['name']}'.compareTo('${b['name']}');
    });
    final files =
        asMaps(snapshot['files'])
            .where(
              (f) => fileFilter == 'favorite'
                  ? f['favorite'] == true
                  : fileFilter == 'recent'
                  ? true
                  : !nested || f['folderId'] == browseFolder,
            )
            .where(matches)
            .toList()
          ..sort(
            (a, b) => ((b['createdAt'] as num?) ?? 0).compareTo(
              (a['createdAt'] as num?) ?? 0,
            ),
          );
    final pending = asMaps(snapshot['jobs'])
        .where(
          (j) =>
              fileFilter != 'favorite' &&
              '${j['title']}'.toLowerCase().contains(
                fileSearch.toLowerCase(),
              ) &&
              ![
                'completed',
                'failed',
                'canceled',
                'cancelled',
              ].contains(j['state']) &&
              (browseFolder.isEmpty || j['folderId'] == browseFolder),
        )
        .toList();
    return [
      if (searchVisible)
        Padding(
          padding: const EdgeInsets.only(bottom: 14),
          child: TextField(
            onChanged: (v) => redraw(() => fileSearch = v),
            decoration: const InputDecoration(
              hintText: '이름으로 검색',
              prefixIcon: Icon(Icons.search),
            ),
          ),
        ),
      if (!nested)
        Padding(
          padding: const EdgeInsets.only(bottom: 18),
          child: Row(
            children: [
              for (final filter in [
                ('all', '전체 (${asMaps(snapshot['files']).length})'),
                ('favorite', '즐겨찾기'),
                ('recent', '최근 항목'),
              ])
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 3),
                    child: ChoiceChip(
                      showCheckmark: false,
                      label: Text(
                        filter.$2,
                        style: const TextStyle(fontSize: 12),
                      ),
                      selected: fileFilter == filter.$1,
                      onSelected: (_) => redraw(() => fileFilter = filter.$1),
                    ),
                  ),
                ),
            ],
          ),
        ),
      if ((settings['rootUri']?.toString() ?? '').isEmpty)
        Card(
          child: ListTile(
            leading: const Icon(Icons.folder_open),
            title: const Text('기본 저장 폴더 만들기'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => mutate('ensureRoot'),
          ),
        ),
      if (folderList.isNotEmpty)
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          childAspectRatio: 1.15,
          crossAxisSpacing: 12,
          mainAxisSpacing: 12,
          children: [for (final folder in folderList) folderCard(folder)],
        ),
      if (folderList.isNotEmpty) const SizedBox(height: 14),
      if (files.isNotEmpty || pending.isNotEmpty)
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          childAspectRatio: 1.18,
          crossAxisSpacing: 6,
          mainAxisSpacing: 6,
          children: [
            for (final job in pending) pendingTile(job),
            for (final file in files) Builder(builder: (_) => mediaTile(file)),
          ],
        ),
      if (folderList.isEmpty && files.isEmpty && pending.isEmpty)
        empty(
          Icons.folder_open_rounded,
          '아직 항목이 없어요',
          '+ 버튼으로 폴더를 만들거나 새 파일을 저장하세요.',
        ),
    ];
  }

  Widget pendingTile(Map<String, dynamic> job) {
    final progress = (job['progress'] as num?)?.toDouble() ?? -1;
    return InkWell(
      onTap: () => redraw(() {
        tab = 0;
        showHistory = true;
      }),
      child: Stack(
        fit: StackFit.expand,
        children: [
          MediaCover(source: asMap(job['asset'])['thumbnail']?.toString()),
          Positioned(
            left: 8,
            right: 8,
            bottom: 8,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 5,
                    vertical: 2,
                  ),
                  color: Colors.black54,
                  child: Text(
                    job['state'] == 'queued'
                        ? '${job['queueReason'] ?? '대기 중'}'
                        : progress < 0
                        ? '대기 중'
                        : '${progress.round()}%',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 12),
                  ),
                ),
                const SizedBox(height: 5),
                LinearProgressIndicator(
                  value: progress < 0 ? null : (progress / 100).clamp(0, 1),
                  minHeight: 4,
                  borderRadius: BorderRadius.circular(4),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget folderCard(Map<String, dynamic> folder) {
    final ids = folderDescendants('${folder['id']}');
    final count = asMaps(snapshot['files'])
        .where((f) => ids.contains(f['folderId']))
        .length;
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => redraw(() {
          browseFolder = '${folder['id']}';
          fileFilter = 'all';
          fileSearch = '';
        }),
        onLongPress: () => folderMenu(folder),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 6, 8, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  folderIcon(folder, size: 30),
                  const Spacer(),
                  IconButton(
                    tooltip: '${folder['name']} 폴더 관리',
                    onPressed: () => folderMenu(folder),
                    icon: const Icon(Icons.more_vert, size: 20),
                  ),
                ],
              ),
              const Spacer(),
              Text(
                '${folder['name']}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                '$count개의 파일',
                style: TextStyle(
                  fontSize: 11,
                  color: Theme.of(pageContext).colorScheme.onSurface
                      .withValues(alpha: .6),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> loadDetails(Map<String, dynamic> file) async {
    final id = '${file['id']}';
    if (!requestedDetails.add(id)) return;
    try {
      final value = await bridge.call('fileInfo', {'id': id});
      if (mounted && value is Map) redraw(() => fileDetails[id] = asMap(value));
    } catch (_) {
      requestedDetails.remove(id);
    }
  }

  Map<String, dynamic> detailFor(Map<String, dynamic> file) => {
    ...file,
    ...?fileDetails['${file['id']}'],
    'name': file['name'],
    'uri': file['uri'],
    'folderId': file['folderId'],
    'favorite': file['favorite'],
  };
  Widget mediaTile(Map<String, dynamic> file) {
    final path = fileDetails['${file['id']}']?['previewPath']?.toString();
    if (path != null && path.startsWith('/') && !File(path).existsSync()) {
      fileDetails.remove('${file['id']}');
      requestedDetails.remove('${file['id']}');
    }
    if (file['missing'] != true &&
        !requestedDetails.contains('${file['id']}')) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) loadDetails(file);
      });
    }
    final data = detailFor(file);
    final duration = durationLabel(data['duration']);
    return Semantics(
      label: '${file['name']}',
      button: true,
      child: InkWell(
        onTap: () => fileDetail(file),
        onLongPress: () => fileMenu(file),
        child: Stack(
          fit: StackFit.expand,
          children: [
            MediaCover(
              source:
                  data['previewPath']?.toString() ??
                  data['thumbnail']?.toString(),
              icon: '${file['mime']}'.startsWith('audio')
                  ? Icons.audio_file_outlined
                  : Icons.image_outlined,
            ),
            if (duration.isNotEmpty)
              Positioned(
                right: 6,
                bottom: 6,
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 5,
                    vertical: 2,
                  ),
                  decoration: BoxDecoration(
                    color: Colors.black.withValues(alpha: .65),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(
                    duration,
                    style: const TextStyle(color: Colors.white, fontSize: 11),
                  ),
                ),
              ),
            if (file['favorite'] == true)
              const Positioned(
                left: 6,
                top: 6,
                child: Icon(Icons.star, color: Colors.amber, size: 18),
              ),
            if (file['missing'] == true)
              const Center(
                child: Icon(Icons.warning_amber, color: Colors.orange),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> fileMenu(Map<String, dynamic> file) async {
    file =
        asMaps(snapshot['files'])
            .where((f) => f['id'] == file['id'])
            .firstOrNull ??
        file;
    final data = detailFor(file);
    final action = await actionSheet(
      title: '${file['name']}',
      subtitle: '${file['mime']} · ${bytesLabel(file['size'])}',
      icon: SizedBox(
        width: 56,
        height: 50,
        child: MediaCover(
          source:
              data['previewPath']?.toString() ?? data['thumbnail']?.toString(),
        ),
      ),
      actions: [
        if ('${file['mime']}'.startsWith('video/') ||
            '${file['mime']}'.startsWith('audio/'))
          ('filePlay', '앱에서 재생', Icons.play_circle_outline),
        ('fileOpen', '열기', Icons.open_in_new),
        ('fileFolder', '폴더에서 보기', Icons.folder_outlined),
        ('fileShare', '공유', Icons.ios_share),
        ('fileRename', '이름 변경', Icons.edit_outlined),
        ('fileMove', '이동', Icons.drive_file_move_outlined),
        (
          'favorite',
          file['favorite'] == true ? '즐겨찾기 해제' : '즐겨찾기',
          Icons.star_outline,
        ),
        ('fileDelete', '삭제', Icons.delete_outline),
      ],
    );
    if (action != null) await fileAction(action, file);
  }

  Future<void> fileAction(String action, Map<String, dynamic> file) async {
    file =
        asMaps(snapshot['files'])
            .where((f) => f['id'] == file['id'])
            .firstOrNull ??
        file;
    final args = <String, dynamic>{'id': file['id']};
    if (action == 'favorite') {
      await mutate('favorite', {
        'kind': 'files',
        'id': file['id'],
        'value': file['favorite'] != true,
      });
      return;
    }
    if (action == 'fileRename') {
      final name = await askName('파일 이름 변경', '${file['name']}');
      if (name == null) return;
      args['name'] = name;
    }
    if (action == 'fileMove') {
      final folder = await chooseFolder(
        initial: file['folderId']?.toString(),
        title: '이동할 폴더',
      );
      if (folder == null) return;
      if (folder.isNotEmpty) args['folderId'] = folder;
    }
    if (action == 'fileDelete' &&
        !await confirm(
          '파일 삭제',
          file['missing'] == true
              ? '이미 삭제된 파일의 앱 기록을 삭제할까요?'
              : '이 파일을 기기에서 삭제할까요?',
        )) {
      return;
    }
    await mutate(action, args);
    if (['fileMove', 'fileRename', 'fileDelete'].contains(action)) {
      fileDetails.remove('${file['id']}');
      requestedDetails.remove('${file['id']}');
    }
  }

  Future<void> fileDetail(Map<String, dynamic> file) async {
    await loadDetails(file);
    if (!mounted) return;
    await Navigator.of(pageContext).push(
      MaterialPageRoute<void>(
        builder: (context) => _FileDetailPage(
          file: () => detailFor(
            asMaps(snapshot['files'])
                    .where((f) => f['id'] == file['id'])
                    .firstOrNull ??
                file,
          ),
          folderName: () =>
              folders
                  .where(
                    (f) =>
                        f['id'] ==
                        (asMaps(snapshot['files'])
                                .where((item) => item['id'] == file['id'])
                                .firstOrNull ??
                            file)['folderId'],
                  )
                  .firstOrNull?['name']
                  ?.toString() ??
              '기본 저장 위치',
          action: (action) => fileAction(action, file),
          menu: () async {
            await fileMenu(file);
            final current = asMaps(snapshot['files'])
                .where((f) => f['id'] == file['id'])
                .firstOrNull;
            if (current == null) {
              if (mounted) navigator.currentState?.pop();
            } else {
              await loadDetails(current);
            }
          },
          openSource: (url) => invoke('openUrl', {'url': url}),
        ),
      ),
    );
  }
}

class _FileDetailPage extends StatefulWidget {
  const _FileDetailPage({
    required this.file,
    required this.folderName,
    required this.action,
    required this.menu,
    required this.openSource,
  });
  final Map<String, dynamic> Function() file;
  final String Function() folderName;
  final Future<void> Function(String) action;
  final Future<void> Function() menu;
  final Future<dynamic> Function(String) openSource;
  @override
  State<_FileDetailPage> createState() => _FileDetailPageState();
}

class _FileDetailPageState extends State<_FileDetailPage> {
  @override
  Widget build(BuildContext context) {
    final file = widget.file();
    final duration = durationLabel(file['duration']);
    final width = (file['width'] as num?)?.toInt() ?? 0;
    final height = (file['height'] as num?)?.toInt() ?? 0;
    final resolution = width > 0 && width < height ? width : height;
    final created = (file['createdAt'] as num?)?.toInt();
    final date = created == null
        ? null
        : DateTime.fromMillisecondsSinceEpoch(created);
    final format = '${file['name']}'.split('.').last.toUpperCase();
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          tooltip: '뒤로',
          onPressed: () => Navigator.pop(context),
          icon: const Icon(Icons.chevron_left),
        ),
        actions: [
          IconButton(
            tooltip: '파일 관리',
            onPressed: () async {
              await widget.menu();
              if (mounted) setState(() {});
            },
            icon: const Icon(Icons.more_vert),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 30),
          children: [
            Stack(
              alignment: Alignment.center,
              children: [
                MediaCover(
                  source:
                      file['previewPath']?.toString() ??
                      file['thumbnail']?.toString(),
                  height: 230,
                  icon: Icons.audio_file_outlined,
                ),
                if ('${file['mime']}'.startsWith('video/') ||
                    '${file['mime']}'.startsWith('audio/'))
                  IconButton.filledTonal(
                    tooltip: '앱에서 재생',
                    onPressed: () => widget.action('filePlay'),
                    icon: const Icon(Icons.play_arrow, size: 38),
                  ),
              ],
            ),
            const SizedBox(height: 16),
            Text(
              '${file['title'] ?? file['name']}',
              style: const TextStyle(fontSize: 23, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 6),
            Text(
              '${platformName(file['platform'])} · $format · ${bytesLabel(file['size'])}',
            ),
            const SizedBox(height: 24),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                for (final a in [
                  ('fileOpen', '열기', Icons.open_in_new),
                  ('fileFolder', '폴더에서 보기', Icons.folder_outlined),
                  ('fileShare', '공유', Icons.ios_share),
                ])
                  Column(
                    children: [
                      IconButton(
                        tooltip: a.$2,
                        onPressed: () => widget.action(a.$1),
                        icon: Icon(a.$3),
                      ),
                      Text(a.$2, style: const TextStyle(fontSize: 12)),
                    ],
                  ),
              ],
            ),
            const SizedBox(height: 24),
            for (final key in ['artist', 'album'])
              if ('${file[key] ?? ''}'.isNotEmpty)
                property(key == 'artist' ? '아티스트' : '앨범', '${file[key]}'),
            for (final warning in (file['warnings'] as List?) ?? const [])
              Text('$warning'),
            property(
              '저장 위치',
              widget.folderName(),
              onTap: () => widget.action('fileFolder'),
            ),
            if (date != null)
              property(
                '생성일',
                '${date.year}년 ${date.month}월 ${date.day}일 ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}',
              ),
            if ('${file['sourceUrl'] ?? ''}'.isNotEmpty)
              property(
                '원본 링크',
                '${file['sourceUrl']}',
                onTap: () => widget.openSource('${file['sourceUrl']}'),
              ),
            property('파일 크기', bytesLabel(file['size'])),
            if ((file['height'] as num? ?? 0) > 0)
              property(
                '해상도',
                '${file['mime']}'.startsWith('image/')
                    ? '${file['width']} × ${file['height']}'
                    : '${resolution}p${(file['fps'] as num? ?? 0) > 0 ? ' ${(file['fps'] as num).round()}fps' : ''}',
              ),
            if (duration.isNotEmpty) property('재생 시간', duration),
          ],
        ),
      ),
    );
  }

  Widget property(String title, String value, {VoidCallback? onTap}) => Column(
    children: [
      ListTile(
        contentPadding: EdgeInsets.zero,
        title: Text(title),
        trailing: SizedBox(
          width: 200,
          child: Text(
            value,
            textAlign: TextAlign.end,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 12,
              color: Theme.of(context).colorScheme.onSurface
                  .withValues(alpha: .6),
            ),
          ),
        ),
        onTap: onTap,
      ),
      const Divider(),
    ],
  );
}
