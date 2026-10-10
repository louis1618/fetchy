part of '../main.dart';

extension _FetchyFolders on _FetchyAppState {
  Set<String> folderDescendants(String id) {
    final result = {id};
    var count = 0;
    while (count != result.length) {
      count = result.length;
      result.addAll(
        folders
            .where((f) => result.contains(f['parentId']))
            .map((f) => '${f['id']}'),
      );
    }
    return result;
  }

  String folderPath(Map<String, dynamic> folder) {
    final parts = <String>['${folder['name']}'];
    var parent = folder['parentId'];
    final visited = {'${folder['id']}'};
    while (parent != null && '$parent'.isNotEmpty && visited.add('$parent')) {
      final item = folders.where((f) => f['id'] == parent).firstOrNull;
      if (item == null) break;
      parts.insert(0, '${item['name']}');
      parent = item['parentId'];
    }
    return parts.join(' / ');
  }

  Widget folderIcon(Map<String, dynamic> folder, {double size = 38}) {
    final color = Color((folder['color'] as num?)?.toInt() ?? 0xff0a6bff);
    return Container(
      width: size + 12,
      height: size + 12,
      decoration: BoxDecoration(
        color: color.withValues(alpha: .13),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Icon(Icons.folder_rounded, color: color, size: size),
    );
  }

  Future<String?> chooseFolder({
    String? initial,
    String title = '폴더 선택',
    String? exclude,
    bool automatic = false,
  }) async {
    var selectedFolder = initial ?? '';
    final excluded = exclude == null ? <String>{} : folderDescendants(exclude);
    const platformOrder = [
      'youtube',
      'instagram',
      'tiktok',
      'x',
      'reddit',
      'pinterest',
      'facebook',
      'other',
    ];
    List<Map<String, dynamic>> availableFolders() =>
        folders.where((f) => !excluded.contains('${f['id']}')).toList()
          ..sort((a, b) {
            int rank(Map<String, dynamic> folder) {
              final index = platformOrder.indexOf(
                '${folder['platform']}'.toLowerCase(),
              );
              return index < 0 ? platformOrder.length : index;
            }

            final order = rank(a).compareTo(rank(b));
            return order != 0 ? order : folderPath(a).compareTo(folderPath(b));
          });
    return showModalBottomSheet<String>(
      context: pageContext,
      isScrollControlled: true,
      builder: (context) => StatefulBuilder(
        builder: (context, update) => SafeArea(
          child: SizedBox(
            height: MediaQuery.sizeOf(context).height * .7,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          title,
                          style: const TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                      IconButton(
                        tooltip: '닫기',
                        onPressed: () => Navigator.pop(context),
                        icon: const Icon(Icons.close, size: 20),
                      ),
                    ],
                  ),
                  const SizedBox(height: 14),
                  Expanded(
                    child: ListView(
                      children: [
                        RadioGroup<String>(
                          groupValue: selectedFolder,
                          onChanged: (v) =>
                              update(() => selectedFolder = v ?? ''),
                          child: Column(
                            children: [
                              const RadioListTile<String>(
                                controlAffinity:
                                    ListTileControlAffinity.trailing,
                                value: '',
                                title: Text('기본 저장 위치'),
                                secondary: Icon(Icons.folder_outlined),
                              ),
                              for (final f in availableFolders())
                                RadioListTile<String>(
                                  controlAffinity:
                                      ListTileControlAffinity.trailing,
                                  value: '${f['id']}',
                                  title: Text(folderPath(f)),
                                  secondary: f['platform'] != null
                                      ? PlatformMark(
                                          '${f['platform']}',
                                          size: 24,
                                        )
                                      : folderIcon(f, size: 24),
                                ),
                            ],
                          ),
                        ),
                        if (automatic)
                          const Padding(
                            padding: EdgeInsets.all(8),
                            child: Text(
                              '기본 저장 위치를 선택하면 플랫폼별 폴더 설정을 따릅니다.',
                              style: TextStyle(fontSize: 12),
                            ),
                          ),
                      ],
                    ),
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.add),
                    title: const Text('새 폴더'),
                    onTap: () async {
                      final created = await createFolderSheet(
                        parentId: selectedFolder,
                      );
                      if (!context.mounted || created == null) return;
                      update(() => selectedFolder = created);
                    },
                  ),
                  FilledButton(
                    onPressed: () => Navigator.pop(context, selectedFolder),
                    child: const Text('적용'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<String?> createFolderSheet({String? parentId}) async {
    if ((settings['rootUri']?.toString() ?? '').isEmpty) {
      if (!await mutate('ensureRoot')) return null;
      if ((settings['rootUri']?.toString() ?? '').isEmpty) return null;
    }
    if (!mounted) return null;
    return showModalBottomSheet<String>(
      context: pageContext,
      isScrollControlled: true,
      builder: (context) => _CreateFolderSheet(
        folders: folders,
        parentIcon: (id) {
          final folder = folders.where((f) => f['id'] == id).firstOrNull;
          return folder == null
              ? const Icon(Icons.folder_outlined)
              : folderIcon(folder, size: 24);
        },
        parentLabel: (id) =>
            folders
                .where((f) => f['id'] == id)
                .firstOrNull
                ?.letPath(folderPath) ??
            '기본 저장 위치',
        initialParent: parentId ?? '',
        path: folderPath,
        pickParent: (id) => chooseFolder(initial: id, title: '상위 폴더'),
        create: (name, parent, color) async {
          final result = await invoke('folderCreate', {
            'name': name,
            if (parent.isNotEmpty) 'parentId': parent,
            'color': color,
          });
          await refresh();
          return result is Map ? asMap(result)['id']?.toString() : null;
        },
      ),
    );
  }

  Future<void> folderMenu(Map<String, dynamic> folder) async {
    final action = await actionSheet(
      title: '${folder['name']}',
      subtitle:
          '${asMaps(snapshot['files']).where((f) => folderDescendants('${folder['id']}').contains(f['folderId'])).length}개의 파일',
      icon: folderIcon(folder),
      actions: [
        ('open', '열기', Icons.open_in_new),
        ('rename', '이름 변경', Icons.edit_outlined),
        ('child', '하위 폴더 만들기', Icons.create_new_folder_outlined),
        ('move', '이동', Icons.drive_file_move_outlined),
        (
          'favorite',
          folder['favorite'] == true ? '즐겨찾기 해제' : '즐겨찾기',
          Icons.star_outline,
        ),
        ('delete', '삭제', Icons.delete_outline),
      ],
    );
    if (action == null) return;
    final id = '${folder['id']}';
    if (action == 'open') await invoke('folderOpen', {'id': id});
    if (action == 'child') await createFolderSheet(parentId: id);
    if (action == 'rename') {
      final name = await askName('폴더 이름 변경', '${folder['name']}');
      if (name != null) await mutate('folderRename', {'id': id, 'name': name});
    }
    if (action == 'move') {
      final parent = await chooseFolder(
        initial: folder['parentId']?.toString(),
        exclude: id,
        title: '이동할 폴더',
      );
      if (parent != null) {
        await mutate('folderMove', {
          'id': id,
          if (parent.isNotEmpty) 'parentId': parent,
        });
      }
    }
    if (action == 'favorite') {
      await mutate('favorite', {
        'kind': 'folders',
        'id': id,
        'value': folder['favorite'] != true,
      });
    }
    if (action == 'delete' &&
        await confirm('폴더 삭제', '파일과 하위 폴더를 상위 폴더로 옮겨 보관하고 이 폴더만 삭제합니다.')) {
      await mutate('folderDelete', {'id': id});
    }
  }

  Future<String?> actionSheet({
    required String title,
    String? subtitle,
    Widget? icon,
    required List<(String, String, IconData)> actions,
  }) => showModalBottomSheet<String>(
    context: pageContext,
    isScrollControlled: true,
    builder: (context) => SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: icon,
              title: Text(
                title,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              subtitle: subtitle == null ? null : Text(subtitle),
            ),
            const SizedBox(height: 16),
            Card(
              child: Column(
                children: [
                  for (var i = 0; i < actions.length; i++) ...[
                    ListTile(
                      leading: Icon(
                        actions[i].$3,
                        color: actions[i].$1.toLowerCase().contains('delete')
                            ? Theme.of(context).colorScheme.error
                            : null,
                      ),
                      title: Text(
                        actions[i].$2,
                        style: TextStyle(
                          color: actions[i].$1.toLowerCase().contains('delete')
                              ? Theme.of(context).colorScheme.error
                              : null,
                        ),
                      ),
                      onTap: () => Navigator.pop(context, actions[i].$1),
                    ),
                    if (i < actions.length - 1)
                      const Divider(indent: 16, endIndent: 16),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

class _CreateFolderSheet extends StatefulWidget {
  const _CreateFolderSheet({
    required this.folders,
    required this.parentLabel,
    required this.parentIcon,
    required this.initialParent,
    required this.path,
    required this.pickParent,
    required this.create,
  });
  final List<Map<String, dynamic>> folders;
  final String Function(String) parentLabel;
  final Widget Function(String) parentIcon;
  final String initialParent;
  final String Function(Map<String, dynamic>) path;
  final Future<String?> Function(String) pickParent;
  final Future<String?> Function(String, String, int) create;
  @override
  State<_CreateFolderSheet> createState() => _CreateFolderSheetState();
}

class _CreateFolderSheetState extends State<_CreateFolderSheet> {
  final name = TextEditingController();
  late String parent;
  int color = 0xffec49b4;
  bool saving = false;
  @override
  void initState() {
    super.initState();
    parent = widget.initialParent;
  }

  @override
  void dispose() {
    name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.fromLTRB(
      20,
      0,
      20,
      MediaQuery.viewInsetsOf(context).bottom + 20,
    ),
    child: SafeArea(
      top: false,
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                const SizedBox(width: 48),
                const Expanded(
                  child: Text(
                    '새 폴더 만들기',
                    textAlign: TextAlign.center,
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                  ),
                ),
                IconButton(
                  tooltip: '닫기',
                  onPressed: saving ? null : () => Navigator.pop(context),
                  icon: const Icon(Icons.close, size: 20),
                ),
              ],
            ),
            const SizedBox(height: 24),
            const Text('폴더 이름'),
            const SizedBox(height: 8),
            TextField(
              controller: name,
              onChanged: (_) => setState(() {}),
              textInputAction: TextInputAction.done,
              decoration: InputDecoration(
                hintText: '예: 여행',
                suffixIcon: IconButton(
                  tooltip: '이름 지우기',
                  onPressed: () => setState(name.clear),
                  icon: const Icon(Icons.close, size: 18),
                ),
              ),
            ),
            const SizedBox(height: 20),
            const Text('상위 폴더'),
            const SizedBox(height: 8),
            Card(
              child: ListTile(
                leading: widget.parentIcon(parent),
                title: Text(widget.parentLabel(parent)),
                trailing: const Icon(Icons.chevron_right),
                onTap: saving
                    ? null
                    : () async {
                        final picked = await widget.pickParent(parent);
                        if (mounted && picked != null) {
                          setState(() => parent = picked);
                        }
                      },
              ),
            ),
            const SizedBox(height: 20),
            const Text('아이콘 색상'),
            const SizedBox(height: 8),
            Wrap(
              spacing: 4,
              children: [
                for (final c in [
                  0xffec49b4,
                  0xffff777b,
                  0xffffc56b,
                  0xffb9dd84,
                  0xff40cdb7,
                  0xff0a6bff,
                  0xffa890f3,
                  0xff9298a2,
                ])
                  SizedBox(
                    width: 38,
                    height: 48,
                    child: IconButton(
                      tooltip: '색상 ${c.toRadixString(16)}',
                      padding: EdgeInsets.zero,
                      onPressed: saving
                          ? null
                          : () => setState(() => color = c),
                      icon: Container(
                        width: 26,
                        height: 26,
                        decoration: BoxDecoration(
                          color: Color(c),
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: color == c ? fetchyBlue : Colors.transparent,
                            width: 2,
                          ),
                        ),
                        child: color == c
                            ? const Icon(
                                Icons.check,
                                color: Colors.white,
                                size: 16,
                              )
                            : null,
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 18),
            FilledButton(
              onPressed: saving || name.text.trim().isEmpty
                  ? null
                  : () async {
                      setState(() => saving = true);
                      final result = await widget.create(
                        name.text.trim(),
                        parent,
                        color,
                      );
                      if (!mounted || !context.mounted) return;
                      if (result != null) {
                        Navigator.pop(context, result);
                      } else {
                        setState(() => saving = false);
                      }
                    },
              child: Text(saving ? '만드는 중…' : '만들기'),
            ),
          ],
        ),
      ),
    ),
  );
}

extension on Map<String, dynamic> {
  String letPath(String Function(Map<String, dynamic>) path) => path(this);
}
