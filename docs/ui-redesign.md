# Fetchy reference UI

## Entry points

- `lib/main.dart`: native snapshot, analysis selections, enqueue contract, job controls and settings.
- `lib/ui/screens.dart`: welcome, main shell, home/recent/history, analysis and transparent share sheet.
- `lib/ui/folders.dart`: folder picker, parent selection, create sheet, context actions and hierarchy paths.
- `lib/ui/media.dart`: search/filters, folder/media grids, lazy previews and file details.
- `lib/ui/theme.dart`, `components.dart`: light/dark tokens, common cover, platform marks, oval brand mark.

## Persistent additions

Room retains its existing generic JSON schema and IDs. Old folders migrate to `parentId: null` and platform colors in Storage.recover. Existing installations with a root or jobs skip welcome; new installs persist `onboarded` after Start.

- folders: `parentId`, `color`, `favorite`, `mappingRootUri`; actual SAF directory URI remains authoritative.
- files: `favorite`, source/title/platform/createdAt and measured duration/dimensions/fps. Existing metadata survives rename/move; unknown values are hidden.
- recentLinks: source URL keyed, successful analysis only, newest 100 entries. Reopen always reanalyzes.
- previews: bounded cache of at most 200 hashed URI JPEGs; no broad gallery permissions.

## Native contracts

`folderCreate(name, parentId?, color)`, `folderMove(id, parentId?)`, `favorite(kind: files|folders,id,value)`, `fileInfo(id)`, `fileFolder(id)`, `openUrl(url)` supplement the existing bridge. Native runtime continues to own downloads and settings.

Folder rename/move recursively re-enumerates documents to update descendant URIs, root grants and jobs. Durable journals remain until all updates finish. Cycles and name collisions fail before provider mutation. Active descendant jobs block mutations. Delete moves real children to the actual parent, preserves nested records, and retargets paused/failed jobs; root folder deletion preserves data in the approved root. Platform mapping follows stable IDs and deleted mappings stay disabled.

## Reference adaptations

App-owned layouts follow the five provided images. Counts, filenames, formats, progress, dates and thumbnails come from real records. Android status bars, share chooser, permission/location pickers and external viewers are OS-owned. The format list retains exact native IDs and conversion/merge information; the compact share sheet opens a quality sheet. Existing defaults and cookie/cache/license controls remain available.

Screenshot fixtures are explicitly labeled test media, seeded only by DesignFixtureDeviceTest and removed by its cleanup method. They are absent from production assets.

Platform folders additionally keep `mappingRootUri` separate from physical `rootUri`: moving a mapped folder preserves its automatic destination by ID, including moves between retained grants. Native move return URIs are normalized to the destination tree. File grids mount only visible sliver children; preview generation follows visibility. Manual cleanup clears preview caches without touching active/paused partial downloads. Onboarding/settings changes request notifications only when jobs are actually pending.

## Reproduce the visual review

Install the release APK and `build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk` on a test device with an approved dedicated SAF root. Run `DesignFixtureDeviceTest#cleanup`, then `#seed`, through the AndroidJUnitRunner. The fixtures contain real downloaded W3Schools images, explicitly named `design-fixture-*`.

```sh
python3 scripts/capture_design.py --serial DEVICE --output .impeccable/review/device
python3 scripts/capture_design.py --serial DEVICE --output .impeccable/review/device --variants
```

The first command captures home, files, folder, detail, action/create sheets, settings, share selection, folder picker and full analysis without enqueueing downloads or creating folders. The second captures welcome, dark home and actual system font scale 1.3, restoring the original font scale in `finally`. Run `DesignFixtureDeviceTest#cleanup` afterward to remove only fixture files/jobs/recent entries and restore the saved theme/onboarding state. Screenshots and test logs are retained separately from production assets.

For emulator diagnostics, `--software-rendering` requests the Flutter Intent flag and writes `render-mode.txt`; confirm the actual backend in Flutter logcat rather than assuming this flag is honored. The final API 35 capture used the emulator host renderer after software-GPU ColorBuffer failures; see the verification logs. Physical captures use the default renderer.

The folder picker keeps platform icons on the left, selection indicators on the right and creation/apply actions outside the scrolling list. Its folder data is reevaluated after creation. Home shortcut colors use the current shell context, including the initial dark render; a removed current folder returns to the real root.

Saving uses only the current video/audio/image tab. Each tab remembers its format selection, but hidden tab selections never enter the enqueue payload or save count. The misleading cross-format “총 2개 선택” notice was removed. Multiple image assets still count as multiple items. Queued jobs show the actual network/retry/concurrency wait reason and expose a restart action without overriding network preferences.
