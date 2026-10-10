part of '../main.dart';

extension _FetchyScreens on _FetchyAppState {
  Widget appShell(BuildContext context) {
    if (shareMode) {
      return PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) invoke('finishShare');
        },
        child: Scaffold(
          backgroundColor: Colors.transparent,
          body: SafeArea(
            child: Align(
              alignment: Alignment.bottomCenter,
              child: Container(
                constraints: BoxConstraints(
                  maxWidth: 560,
                  maxHeight: MediaQuery.sizeOf(context).height * .92,
                ),
                child: Material(
                  color: Theme.of(context).colorScheme.surface,
                  clipBehavior: Clip.antiAlias,
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(26),
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Padding(
                        padding: EdgeInsets.only(top: 10),
                        child: SizedBox(
                          width: 32,
                          height: 4,
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: Color(0xffa9adb5),
                              borderRadius: BorderRadius.all(
                                Radius.circular(3),
                              ),
                            ),
                          ),
                        ),
                      ),
                      ListTile(
                        title: const Text(
                          'Fetchy에 저장하기',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        trailing: IconButton(
                          tooltip: '닫기',
                          onPressed: () => invoke('finishShare'),
                          icon: const Icon(Icons.close),
                        ),
                      ),
                      Flexible(
                        child: SingleChildScrollView(
                          padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
                          child: analysis != null
                              ? selectionBody(context, compact: true)
                              : busy || preview != null
                              ? previewBody(context, compact: true)
                              : Column(
                                  children: [
                                    linkInput(),
                                    if (busy)
                                      const Padding(
                                        padding: EdgeInsets.all(32),
                                        child: CircularProgressIndicator(),
                                      ),
                                  ],
                                ),
                        ),
                      ),
                      if (analysis != null)
                        Padding(
                          padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
                          child: saveButton(),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      );
    }
    if (!loading && settings['onboarded'] == false) return welcome(context);
    final current = folders.where((f) => f['id'] == browseFolder).firstOrNull;
    final detail = (analysis != null || preview != null || busy) && tab == 0;
    final nested = tab == 1 && browseFolder.isNotEmpty;
    final settingsDetail = tab == 2 && settingsFromGear;
    final title = detail
        ? ''
        : tab == 1
        ? '${current?['name'] ?? '파일'}'
        : tab == 2
        ? '설정'
        : showHistory
        ? '다운로드 내역'
        : showRecent
        ? '최근 링크'
        : 'Fetchy';
    return PopScope(
      canPop:
          !(detail || nested || settingsDetail || showHistory || showRecent),
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) navigateBack();
      },
      child: Scaffold(
        appBar: AppBar(
          leading:
              detail || nested || settingsDetail || showHistory || showRecent
              ? IconButton(
                  tooltip: '뒤로',
                  icon: const Icon(Icons.chevron_left),
                  onPressed: navigateBack,
                )
              : null,
          title: Text(
            title,
            style: TextStyle(
              fontSize: nested || tab == 2 || showHistory || showRecent
                  ? 20
                  : 26,
            ),
          ),
          actions: [
            if (detail)
              IconButton(
                tooltip: '원본 링크 열기',
                onPressed: () => invoke('openUrl', {
                  'url':
                      analysis?['sourceUrl'] ??
                      preview?['sourceUrl'] ??
                      url.text.trim(),
                }),
                icon: const Icon(Icons.link),
              ),
            if (tab == 0 && !detail && !showHistory && !showRecent)
              IconButton(
                tooltip: '설정',
                onPressed: () => redraw(() {
                  tab = 2;
                  settingsFromGear = true;
                }),
                icon: const Icon(Icons.settings_outlined),
              ),
            if (tab == 1) ...[
              IconButton(
                tooltip: '파일 검색',
                onPressed: () => redraw(() => searchVisible = !searchVisible),
                icon: const Icon(Icons.search),
              ),
              PopupMenuButton<String>(
                tooltip: '파일 보기 옵션',
                onSelected: (action) {
                  if (action == 'refresh') {
                    refresh();
                  } else {
                    changeStorageLocation();
                  }
                },
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 'refresh', child: Text('새로고침')),
                  PopupMenuItem(value: 'root', child: Text('저장 위치 변경')),
                ],
              ),
            ],
            const SizedBox(width: 8),
          ],
        ),
        body: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 760),
              child: loading
                  ? const Center(child: CircularProgressIndicator())
                  : RefreshIndicator(
                      onRefresh: refresh,
                      child: tab == 1
                          ? fileScroll(context)
                          : ListView(
                              padding: const EdgeInsets.fromLTRB(
                                20,
                                12,
                                20,
                                100,
                              ),
                              children: [
                                if (connectionError != null)
                                  ListTile(
                                    title: Text(connectionError!),
                                    trailing: TextButton(
                                      onPressed: refresh,
                                      child: const Text('재연결'),
                                    ),
                                  ),
                                if (tab == 0 && detail)
                                  analysis != null
                                      ? selectionBody(context)
                                      : previewBody(context),
                                if (tab == 0 && !detail) ...homePage(context),
                                if (tab == 1) ...fileBrowser(context),
                                if (tab == 2) ...groupedSettings(context),
                              ],
                            ),
                    ),
            ),
          ),
        ),
        floatingActionButton: tab == 1
            ? FloatingActionButton(
                backgroundColor: Theme.of(context)
                    .colorScheme
                    .surfaceContainerLowest,
                foregroundColor: Theme.of(context).colorScheme.onSurface,
                elevation: 3,
                shape: const CircleBorder(),
                tooltip: '새 폴더',
                onPressed: () => createFolderSheet(parentId: browseFolder),
                child: const Icon(Icons.add, size: 30),
              )
            : null,
        bottomNavigationBar: detail
            ? SafeArea(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
                  child: analysis == null
                      ? const SizedBox.shrink()
                      : saveButton(),
                ),
              )
            : nested || settingsDetail || showHistory || showRecent
            ? null
            : NavigationBar(
                selectedIndex: tab,
                onDestinationSelected: (value) => redraw(() {
                  tab = value;
                  settingsFromGear = false;
                  showHistory = false;
                  showRecent = false;
                }),
                destinations: const [
                  NavigationDestination(
                    icon: Icon(Icons.home_outlined),
                    selectedIcon: Icon(Icons.home_rounded),
                    label: '홈',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.folder_outlined),
                    selectedIcon: Icon(Icons.folder_rounded),
                    label: '파일',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.settings_outlined),
                    selectedIcon: Icon(Icons.settings),
                    label: '설정',
                  ),
                ],
              ),
      ),
    );
  }

  void navigateBack() => redraw(() {
    if ((analysis != null || preview != null || busy) && tab == 0) {
      analysisEpoch++;
      analysisWaitTimer?.cancel();
      unawaited(invoke('cancelAnalysis'));
      analysis = null;
      preview = null;
      busy = false;
      selected.clear();
    } else if (tab == 1 && browseFolder.isNotEmpty) {
      browseFolder =
          folders
              .where((f) => f['id'] == browseFolder)
              .firstOrNull?['parentId']
              ?.toString() ??
          '';
      fileSearch = '';
    } else if (tab == 2 && settingsFromGear) {
      settingsFromGear = false;
      tab = 0;
    } else {
      showHistory = false;
      showRecent = false;
    }
  });
  Widget welcome(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          children: [
            const Spacer(flex: 2),
            const FetchyLogo(),
            const SizedBox(height: 24),
            const Text(
              'Fetchy',
              style: TextStyle(
                fontSize: 44,
                fontWeight: FontWeight.w800,
                letterSpacing: -1.5,
              ),
            ),
            const SizedBox(height: 14),
            Text(
              '링크 하나로\n원하는 파일을 저장하세요',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 17,
                height: 1.5,
                color: Theme.of(context).colorScheme.onSurface
                    .withValues(alpha: .6),
              ),
            ),
            const Spacer(flex: 3),
            FilledButton(
              onPressed: () =>
                  mutate('settingsSet', {'key': 'onboarded', 'value': true}),
              child: const Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text('시작하기'),
                  SizedBox(width: 8),
                  Icon(Icons.arrow_forward, size: 18),
                ],
              ),
            ),
            const SizedBox(height: 12),
          ],
        ),
      ),
    ),
  );
  Widget linkInput() => TextField(
    controller: url,
    keyboardType: TextInputType.url,
    autocorrect: false,
    onSubmitted: (_) {
      if (!busy) analyze();
    },
    onChanged: (_) => redraw(() {}),
    decoration: InputDecoration(
      hintText: '링크를 붙여넣으세요',
      prefixIcon: const Icon(Icons.link),
      suffixIcon: IconButton(
        tooltip: '링크 붙여넣기',
        onPressed: busy
            ? null
            : () async {
                final data = await Clipboard.getData(Clipboard.kTextPlain);
                if (!mounted || data?.text == null) return;
                redraw(() => url.text = data!.text!);
                await analyze();
              },
        icon: const Icon(Icons.content_paste_outlined),
      ),
    ),
  );
  List<Widget> homePage(BuildContext context) {
    final recent = asMaps(snapshot['recentLinks']);
    final jobs = asMaps(snapshot['jobs']);
    final active = jobs
        .where(
          (j) => ![
            'completed',
            'canceled',
            'cancelled',
            'failed',
          ].contains(j['state']),
        )
        .toList();
    if (showHistory) {
      return [
        if (jobs.isEmpty)
          empty(
            Icons.download_done,
            '다운로드 내역이 없어요',
            '저장한 항목의 진행 상황과 결과가 여기에 표시됩니다.',
          ),
        ...jobs.map(jobTile),
      ];
    }
    if (showRecent) {
      return [
        if (recent.isEmpty)
          empty(Icons.link, '최근 링크가 없어요', '링크를 분석하면 여기에 표시됩니다.'),
        ...recent.map(recentTile),
      ];
    }
    return [
      linkInput(),
      if (url.text.isNotEmpty || busy)
        Padding(
          padding: const EdgeInsets.only(top: 12),
          child: FilledButton(
            onPressed: busy ? null : analyze,
            child: Text(busy ? '링크 분석 중…' : '링크 분석'),
          ),
        ),
      const SizedBox(height: 22),
      GridView(
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 4,
          mainAxisExtent: 64 + MediaQuery.textScalerOf(context).scale(11) * 1.5,
          mainAxisSpacing: 10,
          crossAxisSpacing: 12,
        ),
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),

        children: [
          for (final entry in {
            'youtube': 'YouTube',
            'instagram': 'Instagram',
            'tiktok': 'TikTok',
            'x': 'X',
            'reddit': 'Reddit',
            'pinterest': 'Pinterest',
            'facebook': 'Facebook',
            'more': '더보기',
          }.entries)
            InkWell(
              borderRadius: BorderRadius.circular(14),
              onTap: () => platformLink(entry.key),
              child: Column(
                children: [
                  Container(
                    width: 52,
                    height: 52,
                    decoration: BoxDecoration(
                      color: Theme.of(context)
                          .colorScheme
                          .surfaceContainerLowest,
                      borderRadius: BorderRadius.circular(14),
                    ),
                    alignment: Alignment.center,
                    child: entry.key == 'more'
                        ? const Icon(Icons.more_horiz)
                        : PlatformMark(entry.key, size: 34),
                  ),
                  const SizedBox(height: 7),
                  Text(
                    entry.value,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11),
                  ),
                ],
              ),
            ),
        ],
      ),
      const SizedBox(height: 12),
      Row(
        children: [
          Expanded(child: section('최근 링크')),
          TextButton(
            onPressed: () => redraw(() => showRecent = true),
            child: const Text('전체 보기'),
          ),
        ],
      ),
      if (recent.isEmpty)
        empty(Icons.link, '최근 링크가 없어요', '저장하고 싶은 링크를 붙여넣어 보세요.'),
      ...recent.take(3).map(recentTile),
      const SizedBox(height: 16),
      Row(
        children: [
          Expanded(child: section('다운로드')),
          TextButton(
            onPressed: () => redraw(() => showHistory = true),
            child: const Text('다운로드 내역'),
          ),
        ],
      ),
      ...active.map(jobTile),
      if (active.isEmpty)
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 12),
          child: Text('진행 중인 다운로드가 없어요.'),
        ),
    ];
  }

  Future<void> platformLink(String key) async {
    final sites = {
      'youtube': 'https://www.youtube.com/',
      'instagram': 'https://www.instagram.com/',
      'tiktok': 'https://www.tiktok.com/',
      'x': 'https://x.com/',
      'reddit': 'https://www.reddit.com/',
      'pinterest': 'https://www.pinterest.com/',
      'facebook': 'https://www.facebook.com/',
      'vimeo': 'https://vimeo.com/',
      'soundcloud': 'https://soundcloud.com/',
      'twitch': 'https://www.twitch.tv/',
      'Naver TV': 'https://tv.naver.com/',
      'CHZZK': 'https://chzzk.naver.com/',
      'SOOP': 'https://www.sooplive.com/',
      'KakaoTV': 'https://tv.kakao.com/',
      'Bilibili': 'https://www.bilibili.com/',
      'Douyin': 'https://www.douyin.com/',
      'Weibo': 'https://weibo.com/',
      'VK': 'https://vk.com/',
      'OK.ru': 'https://ok.ru/',
      'Rumble': 'https://rumble.com/',
      'Kick': 'https://kick.com/',
      'Bandcamp': 'https://bandcamp.com/',
      'Mixcloud': 'https://www.mixcloud.com/',
      'Streamable': 'https://streamable.com/',
    };
    if (key == 'more') {
      final choice = await showModalBottomSheet<String>(
        context: pageContext,
        builder: (context) => SafeArea(
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const ListTile(title: Text('더 많은 플랫폼')),
                for (final p in sites.keys.skip(7))
                  ListTile(
                    leading: PlatformMark(p, size: 28),
                    title: Text(platformName(p)),
                    onTap: () => Navigator.pop(context, p),
                  ),
              ],
            ),
          ),
        ),
      );
      if (choice == null) return;
      key = choice;
    }
    await invoke('openUrl', {'url': sites[key]});
  }

  Widget recentTile(Map<String, dynamic> item) => ListTile(
    contentPadding: EdgeInsets.zero,
    leading: SizedBox(
      width: 60,
      height: 52,
      child: MediaCover(source: item['thumbnail']?.toString()),
    ),
    title: Text(
      '${item['title']}',
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
    ),
    subtitle: Text(
      '${item['sourceUrl']}',
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: const TextStyle(fontSize: 11),
    ),
    trailing: Text(
      relativeTime(item['analyzedAt']),
      style: const TextStyle(fontSize: 10),
    ),
    onTap: () {
      redraw(() => url.text = '${item['sourceUrl']}');
      analyze();
    },
  );
  String relativeTime(dynamic value) {
    final date = DateTime.fromMillisecondsSinceEpoch(
      (value as num?)?.toInt() ?? 0,
    );
    final age = DateTime.now().difference(date);
    return age.inMinutes < 1
        ? '방금'
        : age.inMinutes < 60
        ? '${age.inMinutes}분 전'
        : age.inHours < 24
        ? '${age.inHours}시간 전'
        : '${age.inDays}일 전';
  }

  List<Widget> groupedSettings(BuildContext context) {
    final result = <Widget>[];
    var group = <Widget>[];
    void flush() {
      if (group.isEmpty) return;
      result.add(
        Card(
          child: Column(
            children: [
              for (var i = 0; i < group.length; i++) ...[
                group[i],
                if (i < group.length - 1)
                  const Divider(indent: 14, endIndent: 14),
              ],
            ],
          ),
        ),
      );
      group = [];
    }

    for (final widget in settingsPage()) {
      if (widget is Padding && widget.child is Text) {
        flush();
        result.add(widget);
      } else {
        group.add(widget);
      }
    }
    flush();
    return result;
  }

  Widget previewCover(String? source, {double? height}) {
    final epoch = analysisEpoch;
    return MediaCover(
      source: source,
      height: height,
      onImageReady: () {
        if (mounted &&
            epoch == analysisEpoch &&
            !previewImageReported &&
            previewWatch != null) {
          previewImageReported = true;
          debugPrint(
            'FetchyPreview image elapsedMs=${previewWatch!.elapsedMilliseconds}',
          );
        }
      },
    );
  }

  Widget previewBody(BuildContext context, {bool compact = false}) {
    final title = preview?['title']?.toString();
    final source = preview?['sourceUrl'] ?? url.text.trim();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (preview != null)
          if (compact)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: SizedBox(
                width: 60,
                height: 52,
                child: previewCover(preview?['thumbnail']?.toString()),
              ),
              title: Text(
                title ?? '',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: Text(
                '$source',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            )
          else ...[
            previewCover(preview?['thumbnail']?.toString(), height: 200),
            const SizedBox(height: 14),
            Text(
              title ?? '',
              style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 6),
            Text(platformName(preview?['platform'])),
          ],
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 24),
          child: Row(
            children: [
              if (busy && !analysisDelayed)
                const SizedBox.square(
                  dimension: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              else
                Icon(
                  analysisError != null ? Icons.info_outline : Icons.schedule,
                  size: 20,
                ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  analysisError ??
                      (analysisDelayed
                          ? '플랫폼 응답이 지연되고 있어요. 다시 시도하거나 원본을 확인하세요.'
                          : preview != null
                          ? '다운로드 가능한 화질을 확인하고 있어요.'
                          : '미리보기를 불러오고 있어요.'),
                ),
              ),
            ],
          ),
        ),
        if (analysisDelayed || analysisError != null)
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: () => invoke('openUrl', {'url': source}),
                  child: const Text('원본 열기'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: FilledButton(
                  onPressed: analyze,
                  child: const Text('다시 시도'),
                ),
              ),
            ],
          ),
      ],
    );
  }

  Widget saveButton() => FilledButton.icon(
    onPressed: busy || selections.isEmpty ? null : enqueue,
    icon: const Icon(Icons.download_outlined),
    label: Text(
      busy
          ? '저장 중…'
          : '저장하기${selections.length > 1 ? ' (${selections.length}개)' : ''}',
    ),
  );
  Widget selectionBody(BuildContext context, {bool compact = false}) {
    final assets = asMaps(analysis?['assets']);
    final cover = assets.firstOrNull?['thumbnail']?.toString();
    final kinds = [
      'video',
      'audio',
      'image',
    ].where((k) => assets.any((a) => formatsFor(a, k).isNotEmpty)).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (compact)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: SizedBox(
              width: 60,
              height: 52,
              child: previewCover(cover),
            ),
            title: Text(
              '${analysis?['title']}',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
            subtitle: Text(
              '${analysis?['sourceUrl']}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          )
        else ...[
          Stack(
            alignment: Alignment.center,
            children: [
              previewCover(cover, height: 200),
              if (kind == 'video')
                IconButton.filledTonal(
                  tooltip: '원본에서 보기',
                  onPressed: () =>
                      invoke('openUrl', {'url': analysis?['sourceUrl']}),
                  icon: const Icon(Icons.play_arrow, size: 34),
                ),
            ],
          ),
          const SizedBox(height: 14),
          Text(
            '${analysis?['title']}',
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 6),
          Text('${platformName(analysis?['platform'])} · ${assets.length}개 항목'),
        ],
        section('형식 선택'),
        SegmentedButton<String>(
          showSelectedIcon: false,
          style: ButtonStyle(
            backgroundColor: WidgetStateProperty.resolveWith(
              (states) => states.contains(WidgetState.selected)
                  ? compact
                        ? fetchyBlue
                        : Theme.of(context).colorScheme.surfaceContainerLowest
                  : Theme.of(context).colorScheme.surfaceContainerLow,
            ),
            foregroundColor: WidgetStateProperty.resolveWith(
              (states) => compact && states.contains(WidgetState.selected)
                  ? Colors.white
                  : Theme.of(context).colorScheme.onSurface,
            ),
            side: const WidgetStatePropertyAll(BorderSide.none),
          ),
          segments: [
            for (final k in kinds)
              ButtonSegment(
                value: k,
                label: Text(
                  k == 'audio'
                      ? '오디오'
                      : k == 'image'
                      ? '이미지'
                      : '동영상',
                ),
              ),
          ],
          selected: {kind},
          onSelectionChanged: (values) => redraw(() {
            kind = values.first;
            selectDefaults();
          }),
        ),
        if (compact)
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(
              kind == 'audio'
                  ? '오디오 형식'
                  : kind == 'image'
                  ? '이미지'
                  : '화질',
            ),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  assets.isEmpty ? '' : selectedFormatLabel(assets.first),
                  style: const TextStyle(fontSize: 12),
                ),
                const Icon(Icons.chevron_right),
              ],
            ),
            onTap: () => showFormats(assets),
          )
        else
          for (final asset in assets)
            assetOptions(asset, multiple: assets.length > 1),
        if (kind == 'video')
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('저장 포맷'),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  (outputVideoFormat ?? 'mp4').toUpperCase(),
                  style: const TextStyle(fontSize: 12),
                ),
                const Icon(Icons.chevron_right, size: 18),
              ],
            ),
            onTap: () async {
              final format = await showModalBottomSheet<String>(
                context: pageContext,
                builder: (context) => SafeArea(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (final ext in ['mp4', 'webm', 'mkv'])
                        ListTile(
                          title: Text(ext.toUpperCase()),
                          onTap: () => Navigator.pop(context, ext),
                        ),
                    ],
                  ),
                ),
              );
              if (format != null) redraw(() => outputVideoFormat = format);
            },
          ),
        if (kind != 'image' && subtitleOptions.isNotEmpty)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.subtitles_outlined),
            title: const Text('자막'),
            trailing: Text(
              subtitle == null
                  ? '저장 안 함'
                  : '${subtitle!['language']} · ${subtitle!['format'].toString().toUpperCase()}',
            ),
            onTap: subtitleSheet,
          ),
        if (kind == 'audio')
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.edit_note),
            title: const Text('오디오 정보'),
            trailing: const Icon(Icons.chevron_right),
            onTap: audioTagsSheet,
          ),
        section('저장 위치'),
        Card(
          child: ListTile(
            leading: folderId != null
                ? folderIcon(
                    folders.where((f) => f['id'] == folderId).first,
                    size: 24,
                  )
                : const Icon(Icons.folder_outlined),
            title: Text(
              folders
                      .where((f) => f['id'] == folderId)
                      .firstOrNull?['name']
                      ?.toString() ??
                  platformFolderLabel(),
            ),
            trailing: const Icon(Icons.chevron_right),
            onTap: () async {
              final picked = await chooseFolder(
                initial: folderId,
                title: '폴더 선택',
                automatic: true,
              );
              if (picked != null) {
                redraw(() => folderId = picked.isEmpty ? null : picked);
              }
            },
          ),
        ),
        const SizedBox(height: 16),
      ],
    );
  }

  String platformFolderLabel() {
    if (settings['platformFolders'] != true) return rootName;
    final platform = platformName(analysis?['platform']).toLowerCase();
    return folders
            .where((f) => '${f['platform']}'.toLowerCase() == platform)
            .firstOrNull?['name']
            ?.toString() ??
        '기본 저장 위치 / 자동 분류';
  }

  String selectedFormatLabel(Map<String, dynamic> asset) =>
      formatsFor(asset, kind)
          .where((f) => selected.contains(selectionKey(asset['id'], f['id'])))
          .map(
            (f) => f['automatic'] == true ? automaticLabel() : '${f['label']}',
          )
          .firstOrNull ??
      '선택';
  Future<void> showFormats(List<Map<String, dynamic>> assets) async {
    await showModalBottomSheet<void>(
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
                    '화질·포맷 선택',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                  ),
                  Expanded(
                    child: ListView(
                      children: [
                        for (final asset in assets)
                          assetOptions(
                            asset,
                            multiple: assets.length > 1,
                            updateSheet: () => update(() {}),
                          ),
                      ],
                    ),
                  ),
                  FilledButton(
                    onPressed: () => Navigator.pop(context),
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

  Widget assetOptions(
    Map<String, dynamic> asset, {
    required bool multiple,
    VoidCallback? updateSheet,
  }) {
    final allFormats = formatsFor(asset, kind);
    var formats = allFormats;
    if (kind == 'video' && updateSheet == null) {
      final groups = <String, List<Map<String, dynamic>>>{};
      for (final f in allFormats) {
        final height = (f['height'] as num?)?.toInt() ?? 0;
        groups
            .putIfAbsent(height > 0 ? '$height' : '${f['id']}', () => [])
            .add(f);
      }
      formats =
          groups.values
              .map(
                (group) =>
                    group
                        .where(
                          (f) => selected.contains(
                            selectionKey(asset['id'], f['id']),
                          ),
                        )
                        .firstOrNull ??
                    group
                        .where(
                          (f) =>
                              f['ext'] ==
                              (outputVideoFormat ?? settings['videoFormat']),
                        )
                        .firstOrNull ??
                    group.first,
              )
              .toList()
            ..sort(
              (a, b) => ((b['height'] as num?) ?? 0).compareTo(
                (a['height'] as num?) ?? 0,
              ),
            );
      if (formats.length > 4) {
        final current = formats
            .where((f) => selected.contains(selectionKey(asset['id'], f['id'])))
            .firstOrNull;
        formats = formats.take(4).toList();
        if (current != null && !formats.contains(current)) formats.add(current);
      }
    }
    final chosen = allFormats
        .map((f) => selectionKey(asset['id'], f['id']))
        .where(selected.contains)
        .firstOrNull;
    return Column(
      children: [
        if (multiple)
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            title: Text('${asset['title']}'),
            value: chosen != null,
            onChanged: busy || formats.isEmpty
                ? null
                : (value) => redraw(
                    () => selectFormat(
                      asset,
                      kind,
                      value == true
                          ? selectionKey(
                              asset['id'],
                              (preferredFormat(asset, kind) ??
                                  formats.first)['id'],
                            )
                          : null,
                    ),
                  ),
          ),
        RadioGroup<String>(
          groupValue: chosen,
          onChanged: (v) {
            if (!busy) redraw(() => selectFormat(asset, kind, v));
            updateSheet?.call();
          },
          child: Column(
            children: [
              for (final f in formats)
                RadioListTile<String>(
                  contentPadding: EdgeInsets.zero,
                  controlAffinity: ListTileControlAffinity.trailing,
                  enabled: !busy,
                  value: selectionKey(asset['id'], f['id']),
                  title: Text(
                    '${(f['height'] as num? ?? 0) == 2160 && updateSheet == null
                        ? '4K (2160p)'
                        : f['automatic'] == true
                        ? automaticLabel()
                        : f['label'] ?? f['id']}${(f['fps'] as num?) != null && (f['fps'] as num) > 0 ? ' ${(f['fps'] as num).round()}fps' : ''}${updateSheet == null && kind == 'video' && (f['height'] as num? ?? 0) > 0 ? '' : ' · ${f['ext']}'}',
                  ),
                  subtitle:
                      updateSheet != null &&
                          (f['needsMerge'] == true || f['extractAudio'] == true)
                      ? Text(
                          f['needsMerge'] == true ? '영상·오디오 병합' : '오디오 추출/변환',
                        )
                      : null,
                ),
            ],
          ),
        ),
        if (updateSheet == null && allFormats.length > formats.length)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              onPressed: () => showFormats([asset]),
              child: const Text('모든 화질·포맷 보기'),
            ),
          ),
      ],
    );
  }
}
