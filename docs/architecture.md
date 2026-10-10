# 코드 구조

네이티브 상태가 기준이며 Flutter는 메서드 호출과 이벤트 스냅샷으로 화면을 갱신합니다. Morit 백엔드는 없습니다. X 공개 메타데이터에 한해 FxTwitter API를 사용하며 파일 처리와 저장은 기기 내부에서 수행합니다.

| 실제 소스 | 역할 |
| --- | --- |
| [lib/main.dart](../lib/main.dart) | 한국어 UI, 공유 진입, 기본 품질 선택, 현재 미디어 탭의 복수 항목 선택, 대기열·파일·설정 화면 |
| [lib/native_bridge.dart](../lib/native_bridge.dart) | `com.luverse.fetchy/native` MethodChannel, `com.luverse.fetchy/events` EventChannel, 값 변환·기본 설정 |
| [MainActivity.kt](../android/app/src/main/kotlin/com/luverse/fetchy/MainActivity.kt) | 채널 연결, SAF·쿠키 선택기, 공유 Activity, 설정 검증 |
| [Engine.kt](../android/app/src/main/kotlin/com/luverse/fetchy/Engine.kt) | 직접 미디어/yt-dlp 분석·다운로드, 정확한 형식 선택, FFmpeg·QuickJS 실행, 검증·취소 |
| [PreviewMetadata.kt](../android/app/src/main/kotlin/com/luverse/fetchy/PreviewMetadata.kt) | 알려진 게시물 URL 별칭 정규화, 공개 HTML 제목·썸네일 파싱 |
| [BundledPython.kt](../android/app/src/main/kotlin/com/luverse/fetchy/BundledPython.kt) | 동일 번들 모듈 설치·교체, 프로세스 사이 Python 바이트코드 재사용 |
| [Runtime.kt](../android/app/src/main/kotlin/com/luverse/fetchy/Runtime.kt) | 네이티브 대기열, 작업 스레드, 네트워크 정책, 재시도, 알림·재시작 복구 |
| [Store.kt](../android/app/src/main/kotlin/com/luverse/fetchy/Store.kt) | Room `fetchy.db`, 종류별 JSON 레코드와 설정 |
| [Storage.kt](../android/app/src/main/kotlin/com/luverse/fetchy/Storage.kt) | SAF 실제 문서 작업, 파일 게시, 작업 저널·복구 |
| [FetchyDocumentsProvider.kt](../android/app/src/main/kotlin/com/luverse/fetchy/FetchyDocumentsProvider.kt) | 자동 미디어 폴더의 제한된 SAF 제공자, 상대 ID·경로 검증·실제 파일 작업 |
| [Cookies.kt](../android/app/src/main/kotlin/com/luverse/fetchy/Cookies.kt) | 암호화 Netscape 쿠키 저장소와 제한된 WebView 로그인 |
| [build_engine.py](../scripts/build_engine.py) | 고정 엔진 다운로드·해시 확인, 두 ABI용 QuickJS 빌드 |

분석 결과의 `assets[].id`와 `formats[].id`를 `selections[].assetId/formatId`로 전달합니다. Runtime은 분석 결과에서 실제 항목·형식을 찾고 작업에 선택 메타데이터·설정·루트 URI를 저장합니다. Engine도 ID와 선택 형식을 확인합니다. 분석·선택은 한 번에 최대 100개를 처리합니다.

Flutter의 `selections`는 현재 `kind`에 속한 형식만 반환합니다. 탭을 바꿔도 이전 화질 선택은 기억하지만, 영상 탭은 영상만, 오디오 탭은 오디오만 등록합니다. 이미지 탭의 복수 자산 선택은 유지합니다.

작업은 `queued`에서 실행·병합/변환·검증·저장을 거쳐 `completed`로 전환됩니다. `paused`, `canceled`, `failed`도 영속화합니다. 동시 실행은 1~4개이며 네트워크 정책과 제한된 자동 재시도를 적용합니다.

`emit()`은 350ms 동안 변경을 모은 뒤 최신 스냅샷을 전달합니다. 실행기가 정지해도 예약한 마지막 이벤트는 전달하므로 빠른 완료·실패 상태가 누락되지 않습니다. 대기 작업의 `queueReason`은 현재 네트워크 정책·재시도 시간·동시 실행 수로 계산하는 표시용 필드입니다. DB에 저장하지 않으며 Wi-Fi/모바일 데이터 설정을 변경하지 않습니다. 대기 작업 메뉴의 `다시 시작`은 기존 `command(id, resume)`과 백그라운드 예약을 다시 실행합니다.

API 34 이상은 사용자 시작 JobScheduler 작업을 예약하며, 허용된 네트워크에서 사용자 동작에 따른 foreground service도 즉시 시작합니다. 이전 버전은 foreground service를 사용합니다. 실행 소유자 하나만 대기열을 실행하고, 먼저 종료된 실행기의 콜백은 새 실행기를 중단하지 않습니다. 동일한 네트워크 조건의 예약은 반복해서 교체하지 않습니다. UIDT 예약 실패 시 서비스 시작을 시도하며, 둘 다 실패하면 대기 작업에 이유를 기록합니다. 알림 권한 요청보다 실행 예약을 먼저 수행합니다.

프로세스 시작 시 실행 중 상태를 `queued`로 복구하고 저장소 저널을 재처리합니다. 부팅·앱 교체 후에는 앱을 열어 재개하도록 알립니다. 시스템 제한과 원격 미디어 서버 지원에 따라 이어받기 여부가 달라집니다.

YouTube `watch`·`shorts`·`live`·`embed` 및 `youtu.be` 링크는 `list` 쿼리가 있어도 단일 영상으로 분석합니다. 명시적 재생목록과 다른 서비스의 다중 자산은 기존 목록 분석을 유지합니다. 이 규칙과 즉시 실행/소유자 구분은 Morit의 관련 구현을 참고했습니다. Morit 백엔드 호출은 추가하지 않았습니다.

| 참고한 Morit 코드 | Fetchy 적용 |
| --- | --- |
| [분석 runtime.py의 `_analyze_as_playlist`](../../Moring_Morit/services/backend/downloads/media/analysis/engines/runtime.py) | `Engine.analyzeAsPlaylist`의 YouTube 단일 영상/목록 구분 |
| [같은 runtime.py의 `_analyze_fxtwitter`, `_analyze_ytdlp`, `_ytdlp_platform_args`](../../Moring_Morit/services/backend/downloads/media/analysis/engines/runtime.py) | X 익명 원본 추출·MP4 화질 분류, syndication 대체 경로, Instagram 원본 이미지 분석 옵션 |
| [BackendTransferJobService.kt](../../Moring_Morit/android/app/src/main/kotlin/com/luverse/morit/downloads/BackendTransferJobService.kt) | 즉시 서비스 실행과 예약 보완, 실행 소유자 구분·이전 실행의 중지 콜백 차단 |

## 네이티브 채널 계약

`com.luverse.fetchy/native`의 비동기 메서드와 `com.luverse.fetchy/events`의 전체 스냅샷을 사용합니다.

| 메서드 | 입력 → 출력/동작 |
| --- | --- |
| `snapshot` | jobs, folders, files, settings, cookies, engine 맵 |
| `preview` | url → sourceUrl, platform, title, thumbnail 또는 null. 다운로드 선택 정보 없음 |
| `analyze` | url, fullFormats? → sourceUrl, platform, title, assets[{id, title, kind, formats}], canExpandFormats? |
| `cancelAnalysis` | 현재 Activity의 분석·미리보기 취소 |
| `enqueue` | analysis, selections[{assetId, formatId}], videoFormat?, folderId? → 원자적으로 작업 등록 |
| `command` | id, action(pause/resume/cancel/retry/deleteRecord) |
| `chooseRoot` | 시스템 SAF 선택·영속 권한 승인 |
| `folderCreate/Rename/Delete/Open` | name 또는 id/name → 실제 폴더 변경/열기 |
| `fileOpen/Share/Delete/Rename/Move` | id, name? 또는 folderId? → 실제 파일 작업 |
| `settingsSet` | key, value → 검증 후 저장 |
| `cookiesImport/Login/Delete` | platform → 파일 선택/WebView/암호화 자료 삭제 |
| `initialShare/finishShare` | 공유 URL 읽기/공유 Activity 종료 |
| `cleanup/licenses` | 사용하지 않는 임시 파일 정리/번들 라이선스 전문 |

새 작업은 설정·저장 루트를 캡처합니다. videoFormat은 작업별로 선택할 수 있으며 오디오 선택은 원본 또는 M4A/MP3/Opus 추출·변환입니다. 실제 입력 포맷 ID는 그대로 사용하고 출력 컨테이너 변경은 별도 단계입니다.

실행 예약 전에 취소 토큰을 등록하며, 시작 복구가 끝나야 대기열을 실행합니다. 작업 기록을 전부 등록한 뒤 백그라운드 스케줄링에 실패하면 기존 작업을 대기 상태로 유지합니다. 화면이 꺼진 전송·변환에는 제한 시간과 finally 해제를 가진 partial wake lock을 사용합니다.

## Reference UI and local presentation state

See [ui-redesign.md](ui-redesign.md). Flutter presentation is split across `lib/ui/`; downloads remain native-owned. Recent analyses and file/folder favorites are Room JSON records. Nested folders use stable IDs and SAF parent links. File detail metadata is measured during existing ffprobe validation and lazily via Android retrievers; previews are disposable URI-keyed app cache files.

## 공개 미디어 접근과 공유 분석

- X: `https://api.fxtwitter.com/i/status/{postId}` → `tweet.media.all`의 원본 → 실패 시 기기 내 yt-dlp `twitter:api=syndication` → 기본 추출기. 보조 API 요청은 고정 헤더와 게시물 ID만 사용하며 CookieVault에 접근하지 않습니다. 민감 콘텐츠 플래그로 미디어를 임의 차단하지 않습니다.
- X 영상 원본은 HTTPS `video.twimg.com` MP4, 사진은 `pbs.twimg.com/media/`만 허용합니다. HLS·외부 URL·미리보기는 원본으로 선택하지 않습니다. `/vid/가로x세로/`와 `/vid/avc1/가로x세로/`를 읽어 실제 화질을 분류합니다. 영상 화질 ID는 CDN 경로로, 자산 ID는 게시물·미디어 ID로 고정합니다. URL 만료 재분석에서도 같은 선택이 있어야 이어서 처리하며 원래 선택 객체에 검증 메타데이터를 반영합니다. `/video/N`, `/photo/N`, `/i/web/status/`도 처리합니다.
- 영상에서 선택한 M4A/MP3/Opus는 기존 직접 다운로드·FFmpeg 오디오 추출 경로를 사용합니다. GIF에는 오디오 출력을 만들지 않습니다. yt-dlp syndication 분석에서 고른 형식은 다운로드에도 같은 옵션과 익명 모드를 보존합니다.
- 공유 진입 시 설정 스냅샷과 분석을 병렬 요청하되, 저장된 화질 설정을 적용한 뒤 기본 선택을 결정합니다. 엔진 버전 조회는 분석 대기열을 점유하지 않습니다. Activity 종료/새 분석 시 이전 작업을 취소하며 아직 실행하지 않은 예약도 정리합니다.
- 성공한 분석은 Room `analysisCache`에 최대 8개·응답당 512KiB·2분 보관합니다. 정규화한 URL·암호화 쿠키 파일 수정 상태·전체 화질 분석 여부를 키로 사용하며 쿠키 내용은 키에 넣지 않습니다. 앱 프로세스 재시작 후에도 재사용하고 캐시 정리 설정에서 삭제합니다. 만료 CDN URL을 새로 고칠 때는 캐시를 우회합니다.
- 분석은 요청별 socket timeout 8초, 재시도 1회, 추출기 재시도 0회, yt-dlp 실행당 30초 제한을 적용합니다. X 보조 API에는 5초 연결/읽기 제한과 1MiB 응답 제한이 있습니다. 파일 전송의 기존 20초/3회 정책은 유지합니다. 일시 네트워크·삭제/미제공·원본 형식 없음·실제 로그인 제한을 구분해 표시합니다.

외부 보조 API가 공개 미디어를 반환하지 못하면 로그인·삭제·지역/플랫폼 제한이 남을 수 있습니다. 다른 플랫폼의 분석은 기존 로컬 엔진을 사용합니다.

## 1.0.3 미리보기·전송 경로

- `MainActivity.preview` → `Runtime.previews`(2개 스레드) → `Engine.preview`: `title/thumbnail/platform/sourceUrl`만 반환합니다. `assets`가 없으므로 대기열 등록에 사용할 수 없습니다. 다운로드·전체 분석 대기열과 독립적입니다.
- `Engine.preview`는 연결·읽기 3초, 전체 요청 8초의 중단을 적용합니다. 응답은 최대 1MiB이며 HTML은 head가 끝나면 읽기를 멈춥니다. 공개 요청에는 사용자 쿠키를 내보내지 않습니다. 이미지 URL도 공개 주소인지 확인합니다.
- Flutter는 `analysisEpoch`로 늦게 도착한 응답을 무시합니다. 뒤로 가기·Activity 종료·새 요청은 해당 분석/미리보기를 취소합니다. 10초 뒤에는 대기 표시를 다시 시도·원본 열기로 바꾸며 실제 완료·실패를 별도로 반영합니다.
- Instagram `parseInstagramEmbed`는 게시물 ID·공개 GraphVideo/GraphImage·모든 carousel 항목을 확인합니다. 명시된 원본만 다운로드 형식으로 만들며 썸네일을 영상/이미지 원본으로 바꾸지 않습니다. 불완전한 carousel은 전체 엔진으로 돌아갑니다. 표시용 Graph dimensions를 영상 해상도로 쓰지 않고, 저장 시 ffprobe 결과를 기록합니다.
- 1.0.8에서 추가 화질 검색 UI를 제거했습니다. 실제 형식 목록은 설정 이하 최고 또는 실제 최고 화질을 선택합니다. Instagram 임베드에는 `automatic` 선택과 원본 대체 형식을 넣으며 다운로드 단계에서 yt-dlp `res` 정렬로 선택합니다. 화질 확인 시간은 미리보기에 추가되지 않습니다. 실패하면 경고와 원본의 실제 해상도를 기록합니다. 내부 `fullFormats`는 만료 URL·선택 자막 복구에 사용합니다.

### 캐시

| 위치/레코드 | 범위·수명 |
| --- | --- |
| Room `previewCache` | 공개 제목/썸네일, 최대 32개·10분 |
| Room `analysisCache` | 실제 자산·형식, 최대 8개·결과당 512KiB·2분 |
| `noBackupFilesDir/resolved-media` | yt-dlp 해석 결과, 최대 8개·각 2MiB·2분. 쿠키/인증 헤더 제거, 소스·자산·포맷·쿠키 수정 상태 검증 |
| `noBackupFilesDir/extractor-cache` | 공통 플레이어/EJS 처리. 요청 시작 시 32MiB·7일 기준 정리 |
| `noBackupFilesDir/youtubedl-android/yt-dlp/fetchy-modules` | 동일 APK 번들의 설치 코드와 Python 컴파일 결과. 번들/설치 버전 변경 시 재설치 |

URL 별칭은 YouTube 단일 영상, Instagram 동일 shortcode, X 동일 게시물/미디어 번호만 통합합니다. 임의 미디어의 서명·화질 쿼리는 보존합니다. 분석 캐시에는 쿠키 수정 상태와 전체 화질 분석 여부도 포함합니다. 설정의 캐시 정리는 분석·미리보기·해석 결과·플레이어 캐시를 지우며, 설치된 엔진 모듈은 유지합니다.

### 파일 전송

- `httpSource`는 안전한 HTTP 형식에만 직접 전송을 연결합니다. 별도 인증 헤더가 필요한 형식, manifest·fragment 전송은 기존 엔진을 사용합니다. YouTube·TikTok은 각각 GVS 요청 규칙/익명 세션 때문에 기존 yt-dlp 전송을 사용합니다. TikTok은 해석 JSON을 전송에 재사용하지 않습니다.
- `downloadSources`는 선택한 영상·음성 URL을 `work/<job>/streams/0,1`에 저장하고 FFmpeg로 그대로 병합합니다. 기존 Range·ETag 검증을 재사용하고, 일시정지 시 부분 파일을 남겨 다음 Engine에서도 재개합니다. 이어받기 설정이 꺼져 있으면 스트림 임시 폴더도 지웁니다.
- `resolvedInfo` 토큰으로 비공개 해석 JSON을 가져와 `--load-info-json` 전송을 시도합니다. 만료·접근 거부 시 원래 소스와 정확한 자산/포맷을 이용하는 기존 경로로 돌아갑니다. 작업 폴더의 임시 JSON은 종료 시 삭제합니다. 실제 형식이 없으면 임의 화질로 대체하지 않습니다.
- HTTP 원본이 있는 같은 해상도는 기본 선택에서 우선하지만 사용자가 고른 형식은 유지합니다. 엔진 fragment 동시 전송은 3개입니다. 전체 동시 다운로드 수 설정은 유지합니다.
- Runtime의 진행 기록은 같은 단계에서 250ms마다 Room에 반영합니다. 단계 전환·최종 상태는 즉시 기록합니다. 부분 파일 복구는 실제 파일·ETag 기준이므로 DB의 바이트 숫자에 의존하지 않습니다.
- `BundledPython.install`은 앱 내부의 임시 폴더에 최대 2,048개·32MiB를 풀고 경로를 검증한 뒤 설치합니다. 번들 SHA와 설치 버전 마커로 재설치를 판별합니다. yt-dlp/FFmpeg/Python/QuickJS 버전과 플랫폼 클라이언트를 변경하지 않습니다.

### 1.0.4 ZIP 헤더 호환·설치 복구

yt-dlp APK asset은 `#!/usr/bin/env python3\n` 23바이트가 앞에 있는 zipapp입니다. `BundledPython.copyZipPayload`는 실행문을 제외한 ZIP 본문을 임시 archive에 기록합니다. 본문의 중앙 디렉터리/각 엔트리 offset은 이미 본문 기준이므로 바이트 이동 외의 변경은 필요하지 않습니다. 일반 ZIP도 그대로 처리하고, 256바이트를 넘는 실행문·누락된 ZIP 헤더는 거부합니다. 원본 asset SHA·엔진 버전은 유지합니다.

`modules-v3` 설치 마커가 이전/실패 설치를 다시 구성합니다. 정상 설치는 다시 풀지 않아 컴파일 캐시를 유지합니다. 이 과정에서 Room·사용자 파일·쿠키·설정을 변경하지 않습니다. 실패한 기존 작업은 기존 `command(retry)`로 같은 자산·포맷을 다시 실행합니다.

`Storage.folderFor`는 분석의 `X (Twitter)` 별칭을 기본 `X` 플랫폼 매핑으로 처리합니다. Flutter의 `platformName`과 저장 위치 표시에도 같은 별칭을 적용합니다. 사용자가 선택한 폴더 ID·이름 변경·삭제 매핑 정책은 유지합니다.

검사: [번들 JVM 테스트](../android/app/src/test/kotlin/com/luverse/fetchy/BundledPythonTest.kt), [기기 ZIP 읽기·설치 복구](../android/app/src/androidTest/kotlin/com/luverse/fetchy/BundledPythonDeviceTest.kt), [원본 헤더/offset 진단](test-results/x-download-fix/zip-header-diagnosis.json). 최신 Android의 [첫 ZIP 헤더 검사 구현](https://android.googlesource.com/platform/libcore/+/744c095ffc6c0b4bb2ca3853c5eb841146011619%5E2..744c095ffc6c0b4bb2ca3853c5eb841146011619/)을 확인했으며, 실제 Android 16 실행과 APK 구조 검사는 구분합니다.

성능 회귀 확인: [위젯 테스트](../test/widget_test.dart), [URL 캐시 키 테스트](../android/app/src/test/kotlin/com/luverse/fetchy/PreviewUrlTest.kt), [실기기 미리보기·전송·이어받기 테스트](../android/app/src/androidTest/kotlin/com/luverse/fetchy/PreviewDeviceTest.kt), [공유 화면 벤치마크](../scripts/benchmark_share.py). 벤치마크의 Flutter frame/image 시간은 요청 시작부터이며, 접근성 폴링으로 잰 전체 화면 시간에는 별도 오버헤드가 포함됩니다.

### 1.0.5 Instagram 스토리 경로

[PreviewMetadata.kt](../android/app/src/main/kotlin/com/luverse/fetchy/PreviewMetadata.kt)의 `isInstagramStory`는 계정 스토리·숫자 ID가 있는 개별 스토리·하이라이트 주소를 검증합니다. `canonicalMediaUrl`은 스토리의 호스트/끝 슬래시만 정규화하고 쿼리 값을 보존합니다. `mediaPlatform`에서 이 주소를 허용하며 `analyzeAsPlaylist`는 개별 스토리에 `--no-playlist`를 적용합니다. 목록·하이라이트는 여러 자산을 유지합니다.

스토리는 공개 게시물용 `/p/{shortcode}/embed/` 및 공개 미리보기를 건너뛰고 기존 `InstagramStoryIE`로 분석합니다. `request`는 모바일 호스트도 추출기가 지원하는 `www.instagram.com`으로 정규화합니다. `execute → cookieFile → CookieVault.temporary`는 저장된 Instagram 쿠키를 전달하고 `finally`에서 평문 임시 파일을 삭제합니다. 사용자 쿠키 저장 형식·기존 기록은 변경하지 않습니다.

[fetchy_instagram_story.py](../android/app/src/main/assets/fetchy_instagram_story.py)는 번들의 공식 추출기 확장 지점을 사용합니다. API가 `media_type=1`로 명시한 사진에만 `image_versions2`의 가장 큰 원본을 형식으로 추가합니다. 영상 포스터는 제외하며 기존 요청/영상 추출을 재사용합니다. `Engine.initialize`가 앱 내부에 원자적으로 설치하고 `request`의 `--plugin-dirs`로 활성화합니다. 사용자가 설치하는 Plugin 기능은 아닙니다. 원본 yt-dlp asset과 SHA는 유지합니다.

사진은 `fetchy-story-image` 형식·명시적 원본 표식·유효한 스토리 URL을 함께 확인한 뒤 처리합니다. `instagramMediaUri`의 Instagram CDN/HTTPS 검증을 영상 임베드와 공유하며 다운로드는 기존 원본 전송·검증·SAF 저장 흐름을 사용합니다. 썸네일만으로 다운로드 항목을 생성하지 않습니다. [오프라인 번들 검사](../scripts/check_instagram_stories.py)와 [실기기 경로 검사](../android/app/src/androidTest/kotlin/com/luverse/fetchy/PreviewDeviceTest.kt)를 실행할 수 있습니다. 추출기 요청 방식은 [공식 소스](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/instagram.py)에 기반합니다.

## 1.0.6 개별 미디어 지원 확장

[MediaPlatforms.kt](../android/app/src/main/kotlin/com/luverse/fetchy/MediaPlatforms.kt)는 43개 플랫폼의 개별 미디어 URL 분류와 저장 폴더 이름의 공통 기준입니다. `Storage.folderFor`가 이 목록을 재사용하며 기존 폴더 ID·사용자 이름·삭제 매핑을 유지합니다. 새 플랫폼 폴더는 실제 다운로드에서 필요할 때 생성합니다. 프로필 전체·계정 구독·대량 URL 입력은 추가하지 않습니다.

`canonicalMediaUrl`은 Reddit 갤러리를 해당 게시물 주소로, TikTok 개별 주소를 추출기 호스트로, 과거 SOOP 도메인을 현재 도메인으로 연결합니다. SoundCloud 비공개 공유 토큰과 임의 서명 쿼리는 유지합니다. Instagram `/share/p/…`, `/share/reel/…`과 Douyin 단축 주소는 검증된 HTTP 리다이렉트를 거쳐 분석합니다. X Spaces·방송 주소는 게시물 전용 FxTwitter 경로를 건너뛰고 해당 내장 추출기로 연결합니다.

[fetchy_photos.py](../android/app/src/main/assets/fetchy_photos.py)는 TikTok `imagePost.images.imageURL`, Pinterest `images.orig`, Reddit `gallery_data`의 유효한 원본·직접 이미지 게시물, Bluesky 레코드의 원본 blob을 보존합니다. TikTok 사진 링크는 독립적인 `TikTokPhotoIE`로 등록합니다. 기본 lazy 추출기 정규식은 사진 경로를 선택하지 않기 때문입니다. 사진 게시물 음악은 별도 오디오 자산이며 활성 탭의 선택만 등록하는 기존 UI를 사용합니다.

[Instagram 확장](../android/app/src/main/assets/fetchy_instagram_story.py)은 스토리 사진 외에 인증 API의 `media_type=1` 게시물·캐러셀 사진도 보존합니다. `fetchy_original_image`와 `fetchy-image` 형식이 모두 있는 결과만 공통 사진 경로로 전달하고 플랫폼 CDN/원본 blob 주소를 검증합니다. OG 이미지·영상 포스터를 다운로드 원본으로 승격하지 않습니다. 사진 분석에는 `--ignore-no-formats-error`를 사용하지만 파서가 유효한 원본을 찾지 못하면 여전히 실패합니다.

공통 포맷 파서는 코덱 이름이 없는 Naver TV 포맷의 실제 해상도·오디오 비트레이트도 사용하며 명시적인 `none`은 그대로 존중합니다. 영상·오디오가 합쳐진 포맷만 있는 경우에도 해당 영상에서 M4A·MP3·Opus를 추출하는 선택을 제공합니다. 명시적인 오디오 전용 포맷이 있으면 기존 선택을 재사용합니다. 전송·변환·파일 검증·SAF 게시·작업 복구는 기존 실행기를 그대로 사용합니다. 분석 캐시 키는 새 규칙에 맞춰 갱신하며 다운로드 작업/파일 DB 마이그레이션은 필요하지 않습니다.

등록·원본 응답 검사·실제 다운로드 결과는 [플랫폼 지원 범위](platform-support.md)와 [검증 현황](verification.md)에 구분합니다.

## 1.0.7 자동 저장소

[FetchyDocumentsProvider](../android/app/src/main/kotlin/com/luverse/fetchy/FetchyDocumentsProvider.kt)는 Android가 허용한 앱 전용 미디어 폴더의 SAF 진입점입니다. 기본 `rootUri`는 안정적인 상대 ID를 사용하고, 기존 Storage 게시·폴더 계층·검증·복구 로직에 연결합니다. Runtime 시작과 등록 시 `Storage.ensureRoot`를 호출하며 저장 위치가 이미 있으면 변경하지 않습니다. Flutter의 `ensureRoot`는 저장·폴더 생성 시 초기화 재시도이고 `chooseRoot`는 설정이나 파일 메뉴에서 사용자가 위치를 변경할 때 사용합니다. MediaStore 스캔은 기존 외부 문서와 새 앱 문서 모두 처리합니다.

파일 삭제는 기존 URI가 조회되면 그대로 수행하고, 삭제된 tree URI 검증 예외는 `presentInParent`의 실제 상위 폴더 목록으로 확인합니다. 접근 불가를 부재로 간주하지 않으며, 파일/폴더 상태의 소유권·DB 형식·이전 승인 위치의 URI는 유지합니다. 기본 위치의 앱 제거 정책은 [저장소 문서](storage-cookies.md)에 설명합니다.

## 1.0.8 선택 기능

[기능별 데이터·채널·제한](download-extras.md)을 참고하세요. `LoginChecks`, `DownloadFailure`, `Diagnostics`, `MediaExtras`, `PlaybackActivity`를 추가하고 기존 `Storage` 전송 저널과 `Runtime` 백그라운드 실행기를 확장합니다. 새 서버·Flutter 재생 플러그인·DB 버전 변경은 없습니다. 기존 레코드에 선택적 JSON 필드를 저장합니다.
