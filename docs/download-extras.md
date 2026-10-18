# Fetchy 1.0.8 · 다운로드 보조 기능

기존 다운로드 엔진·Room 레코드·Content URI를 확장합니다. 추가 서버와 미디어 라이브러리는 없습니다. [계획](feature-upgrade-plan.md), [검증 결과](verification.md), [설치 APK](../artifacts/Fetchy-arm64.apk).

## 자동 화질

- [Flutter 선택](../lib/main.dart)·[네이티브 선택 규칙](../android/app/src/main/kotlin/com/luverse/fetchy/MediaSelection.kt): `preferredFormat`은 설정 이하의 실제 최고 해상도, 최고 설정은 최대 해상도를 선택합니다. 범위 내 형식이 없으면 제공된 최소 해상도를 표시합니다. 영상·오디오 선택은 현재 탭으로 제한합니다.
- [Engine](../android/app/src/main/kotlin/com/luverse/fetchy/Engine.kt): Instagram 공개 임베드에 `automatic` 영상 선택과 `originalFallback`을 포함합니다. 미리보기 단계의 추가 분석 UI는 없습니다.
- 다운로드 시작 후 자동 형식은 기존 yt-dlp를 최대 10초 실행해 `bestvideo+bestaudio/best`, `--format-sort res:설정값` 또는 `res`로 선택합니다. `res`는 세로 영상도 고려합니다. 실제 자산 ID·게시물 항목을 유지합니다. 임베드의 숫자 PK는 번들 추출기의 shortcode 규칙으로 변환해 비교합니다.
- 확인 실패는 제공된 원본으로 처리하고 경고를 보존합니다. ffprobe의 실제 해상도·FPS·시간을 완료 파일에 기록합니다. 수동 선택은 기존 정확한 형식 보존 정책을 따릅니다.
- 수정 시 자동 선택 ID와 실제 엔진 형식 ID를 구분하세요. 임베드 표시 치수를 실제 영상 해상도로 표시하지 마세요.

## 로그인 유효 여부

- [LoginChecks](../android/app/src/main/kotlin/com/luverse/fetchy/LoginChecks.kt): `check`, `states`, `classify`. [Cookies](../android/app/src/main/kotlin/com/luverse/fetchy/Cookies.kt): 암호화·허용 도메인·만료 검사. [설정 UI](../lib/main.dart): 플랫폼 메뉴의 로그인 확인.
- `cookiesCheck(platform)` → 만료되지 않은 임시 쿠키 → 해당 서비스의 HTTPS 인증 응답 → Room `loginChecks` → `snapshot.loginStates`.
- 상태: `none`, `expired`, `unchecked`, `valid`, `invalid`, `unknown`. 확인 결과는 5분간 재사용하며 쿠키 변경 시 무효화합니다. HTTP 403·네트워크 오류·인증 근거 없는 HTTP 200은 `unknown`입니다.
- YouTube·Instagram·X·TikTok·Reddit·Twitch·Bilibili·Weibo는 서비스별 인증 필드를 검사합니다. 다른 등록 사이트는 명시적인 로그인 상태 필드가 있을 때만 판단합니다. 검증 불가를 성공으로 표시하지 않습니다.
- 응답 본문·계정 ID는 저장하지 않고 임시 평문 쿠키는 `finally`에서 삭제합니다. 쿠키 확인은 다운로드나 미리보기의 선행 조건이 아닙니다.
- 수정 시 플랫폼마다 실제 응답 근거를 확인하세요. Instagram 인증 성공을 Threads 인증 성공으로 처리하지 않습니다. 로그인 성공과 콘텐츠 접근·멤버십 자격은 별개입니다.

## 실패 안내·진단 공유

- [DownloadFailure](../android/app/src/main/kotlin/com/luverse/fetchy/DownloadFailure.kt): `FailureKind.from`으로 취소·공간·저장 권한·작업 중·충돌·네트워크·요청 제한·멤버십·로그인·연령·삭제·형식·DRM·미지원·엔진 오류를 분류합니다.
- [Runtime](../android/app/src/main/kotlin/com/luverse/fetchy/Runtime.kt): 작업에 `errorCode/errorHelp` 저장, 재시도 가능 오류만 자동 재시도. [MainActivity](../android/app/src/main/kotlin/com/luverse/fetchy/MainActivity.kt): 채널 오류에 코드·안내 반환.
- [Diagnostics](../android/app/src/main/kotlin/com/luverse/fetchy/Diagnostics.kt): `report/share` → 캐시 JSON → FileProvider → 사용자 선택 공유. 설정 또는 실패 작업 메뉴에서 `diagnosticsShare(id?)`를 호출합니다.
- 앱/Android/기기/엔진 버전, 허용 설정, 작업 상태·오류 분류만 내보냅니다. URL·제목·쿠키·계정·헤더·원시 로그는 제외합니다. 진단 파일은 하루 후 다음 공유에서 정리합니다.
- [shared_paths.xml](../android/app/src/main/res/xml/shared_paths.xml)은 진단 캐시만 노출합니다. 새 진단 필드는 허용 목록에 추가하고 비밀 값 제외 테스트를 갱신하세요.

## 자막 SRT/VTT

- [Engine](../android/app/src/main/kotlin/com/luverse/fetchy/Engine.kt)의 `attachExtras/subtitleSource`는 실제 `subtitles/automatic_captions` 언어를 분석합니다. VTT/SRT/ASS/SSA 원본을 처리하며 live chat은 제외합니다.
- Flutter 자산에는 언어·이름·자동 여부·불투명 토큰만 전달합니다. 실제 자막 URL·공개 요청 헤더는 `noBackupFilesDir/subtitle-sources`에 원자적으로 저장합니다. 자산·원본 해시·쿠키 수정 상태·1시간 수명을 검사합니다.
- [선택 시트](../lib/ui/download_extras.dart): 선택한 자산에 공통으로 제공되는 언어와 SRT/VTT를 고릅니다. `enqueue.subtitle = {language, automatic, format}`.
- [MediaExtras](../android/app/src/main/kotlin/com/luverse/fetchy/MediaExtras.kt): 미디어 처리 후 선택 자막만 요청 → 크기 제한 8MiB → FFmpeg 변환 → HTML/JSON·타임스탬프 검증 → 작업 `sidecars`.
- 만료 URL은 같은 자산·언어·자동 여부로 재분석합니다. 다른 언어로 대체하지 않습니다. 별도 인증 헤더가 필요한 자막 원본은 저장하지 못할 수 있습니다.
- [Runtime](../android/app/src/main/kotlin/com/luverse/fetchy/Runtime.kt)와 [Storage](../android/app/src/main/kotlin/com/luverse/fetchy/Storage.kt)가 실제 미디어와 같은 폴더·루트에 별도 파일로 게시합니다. 안정된 파일 ID는 `작업ID-sub-언어`이며 `subtitleFor/language/automatic`을 저널에도 저장합니다.
- 부가 파일 실패는 미디어의 완료를 취소하지 않고 경고를 표시합니다. 미완료 게시 저널이 참조하는 내부 파일은 정리에서 제외합니다. 앱 내 플레이어에 자막을 표시하거나 영상에 삽입하는 기능은 포함하지 않습니다.

## 오디오 태그·커버

- 분석 자산의 실제 `track/artist/album/creator`를 보존합니다. [오디오 정보 시트](../lib/ui/download_extras.dart)에서 제목·아티스트·앨범을 수정하고 오디오 작업에만 `audioTags`를 전달합니다.
- [MediaExtras](../android/app/src/main/kotlin/com/luverse/fetchy/MediaExtras.kt)의 `audio/writeAudio`: 기존 변환 후 스트림 복사로 태그를 기록합니다. MP3/M4A는 attached picture, Opus는 `METADATA_BLOCK_PICTURE`를 사용합니다.
- 커버는 HTTPS 썸네일만 최대 8MiB 요청하고 디코드 크기·JPEG 64KiB 제한을 적용합니다. 커버 요청 실패는 태그와 오디오를 보존합니다. 메타데이터 처리 실패도 원본 오디오와 경고를 보존합니다.
- 설정 `audioMetadata/audioCover`는 기본 true이며 기존 설정을 덮어쓰지 않습니다. 두 설정은 독립적으로 적용되어 태그를 끄고 커버만 저장할 수도 있습니다. 최종 ffprobe의 실제 제목·아티스트·앨범을 파일 상세에 기록합니다. 커버 스트림은 실제 동영상으로 간주하지 않습니다.

## 앱 내 재생

- [PlaybackActivity](../android/app/src/main/kotlin/com/luverse/fetchy/PlaybackActivity.kt): 앱 파일 ID → Room에 기록된 로컬 `content://` URI → Android `MediaPlayer`.
- 파일 상세의 재생 버튼 또는 파일 메뉴 `앱에서 재생`으로 진입합니다. 재생/일시정지/탐색, 영상 비율 보존, 오디오 커버, 음성 포커스, 화면 종료·회전 상태와 리소스 해제를 처리합니다.
- Activity는 외부에 공개하지 않습니다. 네트워크 URL 재생·배경 오디오 서비스는 없습니다. 기기 디코더에서 지원되지 않는 형식은 기존 시스템 앱 열기를 사용합니다.

## 기존 자료의 저장 위치 이전

- [변경 시트](../lib/ui/download_extras.dart): 새 다운로드부터 적용 또는 기존 파일도 이전 → `chooseRoot(migrate)` → SAF 승인 → `Storage.prepareMigration`.
- 현재 루트의 기록된 최상위 폴더와 그 안의 실제 내용, 루트 직속 등록 파일을 대상으로 합니다. 다른 과거 루트와 등록되지 않은 루트 직속 자료는 남습니다.
- 진행 중 다운로드·다른 미완료 파일 작업, 원본 내부 목적지를 제한합니다. 기존 `transfer/finishMove/copyTree/fingerprint`를 사용하며 같은 이름은 새 이름으로 보존합니다. 검증 성공 후 원본을 삭제합니다.
- `folderOps/storage-migration`의 항목별 `done`과 개별 이동 저널을 저장합니다. Runtime이 기존 백그라운드 실행기·알림·wake lock으로 실행하며 OS 중지 시 대기, 오류 시 실패 상태·해결 안내·재시도를 제공합니다.
- 하위 폴더/파일 URI, 플랫폼 매핑 ID, 즐겨찾기와 일시정지 작업의 목적지를 갱신합니다. 사라진 자료의 레코드는 원래 위치에 남기며 마지막 요약 `settings.lastMigration`에 수를 저장합니다.
- 모든 항목 처리 후 새 루트를 확정합니다. 이전 권한을 해제하지 않습니다. 기본 Android/media 위치의 앱 제거 시 삭제 정책은 계속 적용됩니다.
- 수정 시 원본 삭제 순서, 검증 저널·매핑·하위 URI 재연결을 함께 확인하세요. 중단 시 저널을 삭제해 재시작을 흉내 내지 마세요.

## 검증 파일

- 1.0.9의 파일 날짜 선택은 [저장소·쿠키 문서](storage-cookies.md#109-x-인증과-파일-날짜)에 있습니다. 원본 게시 시각 또는 현재 저장 시각을 파일 수정 시각·날짜 파일명·상세·게시 복구에 사용하며 갤러리 갱신 권한은 별도로 기록합니다.

- [Flutter 회귀](../test/widget_test.dart), [오류·자동 선택 JVM 검사](../android/app/src/test/kotlin/com/luverse/fetchy/DownloadFailureTest.kt).
- [Android 기능 검사](../android/app/src/androidTest/kotlin/com/luverse/fetchy/FeatureUpgradeDeviceTest.kt): 인증 응답 근거·진단 비밀 제외·실제 FFmpeg 태그/커버·자막·로컬 재생/탐색·이전 복구·자막 게시 복구·자동 화질 원본 저장·복사 후 원본 정리 중단 복구.
- 실제 계정 인증·모의 응답, 외부 서비스 성공·실패와 건너뛴 항목은 [검증 현황](verification.md)에 구분합니다.
