# 검증 결과

2026-10-08 검증. 최신 산출물은 **1.0.5+6**이며 로컬 설치용 debug 키로 서명한 **release 빌드**입니다. APK는 [ARM64](../artifacts/Fetchy-arm64.apk), [x86_64](../artifacts/Fetchy-x86_64.apk), [SHA-256](../artifacts/SHA256SUMS.txt)을 제공합니다. 최신 스토리 URL·쿠키 경로 결과는 마지막 절에 기록하고 이전 버전의 검증 기록도 보존합니다.

## 초기 독립 앱 실행 결과

| 항목 | 결과·증거 |
| --- | --- |
| Flutter 분석 | [오류·경고 없음](test-results/flutter-analyze.txt) |
| Flutter 테스트 | [11개 통과](test-results/flutter-test.txt): 채널 계약·선택·공유·폴더·쿠키·첫 저장 승인/취소 |
| JVM 테스트 | 7개 통과: [파일명/UTF-8 제한](test-results/TEST-com.luverse.fetchy.StorageNamesTest.xml), [네트워크 정책](test-results/TEST-com.luverse.fetchy.NetworkPolicyTest.xml) |
| ARM64 릴리스 | Android 12/API 31, Silver Max Pro 2 실기기 설치·실행 |
| x86_64 릴리스 | API 35 Google APIs 에뮬레이터 설치·실행 |
| 엔진·저장·쿠키 | 두 ABI에서 영상/MP3/JPEG/WebP 다운로드·SAF 게시, 영상→M4A/MP3/Opus 추출, YouTube 분석·다운로드·병합/검증, 부분 파일 보존·새 Engine에서 이어받기, 예약 직후 취소, 쿠키 암호화·도메인 필터 통과 |
| 대기열 | 다중 등록, 일시정지·재개·취소·재시도·기록 삭제와 영상/오디오 2개 동시 작업 통과 |
| 복구·백그라운드 | 두 ABI에서 실제 프로세스 강제 종료 후 부분 파일·Room 상태 복구, Activity 종료 후 FGS/UIDT 저장 완료 통과 |
| 폴더·파일 | 시스템 폴더 승인, 실제 CRUD·파일 보존, 기본 폴더 이름 변경 후 플랫폼 매핑 유지, 삭제 후 자동 재생성 방지·일시정지/실패/취소 작업 재연결, DB에 없는 중첩 파일 보존까지 통과 |
| 외부 앱 | 실제 파일 열기·공유 창·폴더 위치 열기 통과. 공유 창에서 메시지를 전송하지 않음 |
| 재부팅·화면 꺼짐 | [API 35 에뮬레이터 실제 재부팅 후 부분 파일 복구·화면을 끈 상태에서 저장 완료](test-results/emulator-reboot-screenoff.txt) 통과 |
| 공유 팝업 | 실기기에서 설정 앱 위에 투명 공유 팝업 표시·분석, 뒤로 가기로 원래 앱 복귀 확인 |
| 16KB 정렬 | [APK 내 ELF 520개 검사](test-results/native-alignment.json): ARM64 259개, x86_64 261개 모두 16KB 이상. FFmpeg zip 교체본의 SHA가 실제 APK payload와 일치 |
| 서버 의존 | `lib/`, Android main 소스·Gradle에서 Morit 채널/백엔드·Supabase·Firebase·Cobalt 설정/클라이언트 없음. 콘텐츠 원본 사이트에는 직접 접속 |

실기기 로그: [엔진 8개](test-results/MTK0000903071707360/engine-storage-cookies.txt), [대기열 2개](test-results/MTK0000903071707360/queue-controls-concurrency.txt), [백그라운드 복구](test-results/MTK0000903071707360/background-recovery.txt), [실제 파일/폴더 열기](test-results/MTK0000903071707360/external-access.txt). 에뮬레이터의 동일 점검도 `test-results/emulator-5554/`에 기록했습니다. [기본 폴더·미등록 중첩 파일 추가 검증](test-results/device-platform-folders.txt)도 2개 통과했습니다.

복구 테스트의 초기 실패는 상태를 `queued`로만 허용한 검사 때문에 발생했습니다. 이미 복구·실행되어 `saving`에 도달한 정상 상태도 허용하고, 이전 fixture를 정리한 뒤 실제 프로세스를 종료하는 방식으로 다시 검증했습니다. [초기 검사 로그](test-results/MTK0000903071707360/background-recovery-prior-test-assertion.txt)를 보존합니다.

## 실제 사이트

| 사이트/입력 | 관찰 결과 |
| --- | --- |
| YouTube `jNQXAC9IVRw` | 분석·소형 영상 다운로드·후처리·미디어 검증 통과 |
| Instagram `BQ0eAlwhDrw` | [3개 미디어 분석, 선택 영상 39,753 bytes 다운로드](test-results/social-instagram.txt) |
| TikTok `6748451240264420610` | [해당 공개 테스트 링크 추출 실패](test-results/social-tiktok.txt). 플랫폼 전체 불가로 일반화하지 않음 |
| X `705235433198714880` | [해당 공개 테스트 링크 추출 실패](test-results/social-x.txt). 플랫폼 전체 불가로 일반화하지 않음 |
| W3Schools 영상·MP3·JPEG, Google WebP | 실제 HTTPS 다운로드·미디어 검증·파일 저장 통과 |

## 남은 검증 범위·제한

- 실제 사용자 계정의 WebView 로그인과 인증 콘텐츠는 사용자 자격 증명 없이 검증하지 않았습니다. WebView 차단 시 Netscape 쿠키 파일 가져오기를 사용합니다.
- 실제 16KB 페이지 하드웨어 실행은 미검증이며 위 결과는 패키지 전체 ELF의 정적 검사입니다.
- 장시간 Doze/제조사 절전, 실제 모바일 데이터 전환, 저장 공간 완전 소진, SAF 권한 회수·제공자 교체·모든 외부 동시 수정 조합은 별도 검증 범위입니다. 네트워크 설정은 정책 단위 테스트와 실제 Wi-Fi 전송으로 확인했습니다.
- DRM은 지원하지 않습니다. 서비스 정책·삭제·로그인 제한에 따라 링크별 성공 여부가 달라집니다. 폴더 열기·갤러리 인덱싱은 Android 제공자·시스템 앱에 따라 다릅니다.
- 외부 배포용 서명 키·스토어 게시·대응 소스 공개 묶음은 이 로컬 구현 범위에 포함하지 않습니다.

## 레퍼런스 UI 개편 검증

기존 로그는 위에 보존하고, 이번 개편 결과는 `test-results/redesign/`에 분리했습니다. Morit 소스는 수정하지 않았습니다.

| 항목 | 결과·증거 |
| --- | --- |
| Flutter 분석 | [오류·경고 없음](test-results/redesign/flutter-analyze.txt) |
| Flutter 테스트 | [21개 통과](test-results/redesign/flutter-test.txt): 기존 채널·선택·권한·쿠키 계약, 시작 화면, 하위 폴더 +, 즐겨찾기, 최근 URL, 선택 시트 순서/오른쪽 라디오/고정 생성 버튼, 320×700·글꼴 1.3, 명시적 텍스트 대비, 최초 다크 화면, 사라진 현재 폴더의 루트 복귀 |
| JVM | [네트워크 4개](test-results/redesign/TEST-com.luverse.fetchy.NetworkPolicyTest.xml), [이름 검증 3개](test-results/redesign/TEST-com.luverse.fetchy.StorageNamesTest.xml) 통과 |
| Android 12 실기기 | [엔진·대기열·계층 기본 12개](test-results/redesign/physical-native-final.txt), [계층·기존 기록 마이그레이션·이동 저널 복구 3개](test-results/redesign/physical-hierarchy-migration.txt) 통과. 중복된 계층 기본 검사를 제외한 고유 테스트 14개 |
| API 35 에뮬레이터 | [엔진·대기열·계층 기본 12개](test-results/redesign/emulator-native-final.txt), [계층·마이그레이션·이동 저널 복구 3개](test-results/redesign/emulator-hierarchy-migration.txt) 통과. 고유 테스트 14개 |
| 백그라운드·복구 | 실제 강제 종료 후 [실기기](test-results/redesign/MTK0000903071707360-background-restore.txt)·[에뮬레이터](test-results/redesign/emulator-5554-background-restore.txt)의 부분 다운로드 복구·Activity 종료 후 저장 완료 통과 |
| 실제 시스템 앱 | [실기기](test-results/redesign/MTK0000903071707360-open-share-location.txt)·[에뮬레이터](test-results/redesign/emulator-5554-open-share-location.txt)에서 Content URI 열기·공유·실제 폴더 위치 열기 통과 |
| 계층 저장소 | 실제 생성/이름 변경/이동/삭제, 자손 작업 중 변경 차단, 순환 이동 거부, 삭제 시 상위 폴더로 파일 보존, 즐겨찾기 유지, 삭제·이동 저널의 새 Store 복구 통과 |
| 기존 기록 | `parentId`·색상·즐겨찾기가 없던 평면 폴더를 두 번 복구해도 ID·URI·기존 파일·파일 즐겨찾기 유지 |
| 미리보기 | [실기기](test-results/redesign/physical-design-previewCacheRegeneratesAndKeepsFavorites.txt)·[에뮬레이터](test-results/redesign/emulator-design-previewCacheRegeneratesAndKeepsFavorites.txt)에서 실제 이미지 크기 조회·캐시 삭제/재생성·즐겨찾기 보존 통과 |
| 빌드·ABI | [Flutter release 빌드](test-results/redesign/flutter-release-build.txt). 마지막 Flutter 변경 전후 [네이티브 Dex 일치](test-results/redesign/native-dex-consistency.json); 네이티브 기기 테스트 결과에 해당하는 구현이 최종 APK에도 동일하게 포함됨 |
| 16KB 정렬 | [최종 APK ELF 520개](test-results/redesign/native-alignment.json), 16KB 미만 없음. 실제 16KB 하드웨어 실행 결과는 아님 |
| 백엔드 연결 | [소스 검사](test-results/redesign/server-dependency-scan.json): Morit 채널·Supabase·Firebase·Cobalt·백엔드 URL 참조 없음. 이 검사는 전체 네트워크 패킷 캡처를 대신하지 않음 |

실기기 첫 재검증의 USB 연결 중단은 [별도 로그](test-results/redesign/physical-native-interrupted.txt)에 보존했습니다. 재연결 후 12개와 복구/외부 앱 검사를 다시 통과했습니다. 에뮬레이터 소프트웨어 GPU의 [ColorBuffer 오류](test-results/redesign/emulator-host-swangle.txt)로 화면 캡처가 중단되어, 호스트 렌더러로 재시작 후 14개 화면을 다시 캡처했습니다. 진단용 Flutter software-rendering Intent 옵션도 요청했지만 [실제 엔진 로그](test-results/redesign/emulator-renderer.txt)는 Impeller OpenGLES로 기록되므로 CPU 렌더링 성공으로 해석하지 않습니다. APK 구현/설정은 변경하지 않았습니다.

최종 APK로 각 기기에서 캡처한 14개 화면의 [해시 목록](test-results/redesign/screenshot-manifest.json)을 보관합니다. 캡처 후 테스트 파일·작업·fixture 링크를 [실기기](test-results/redesign/physical-design-final-cleanup.txt)·[에뮬레이터](test-results/redesign/emulator-design-final-cleanup.txt)에서 정리했고 기존 테마·온보딩·글꼴 배율을 복원했습니다.

화면 비교 증거와 실제 데이터/Android 시스템 화면의 적용 범위는 [UI 검토 기록](../.impeccable/review/review.md)에 기록합니다. 같은 이미지의 픽셀 일치율을 측정한 결과는 아닙니다. 네트워크·서비스별 기존 제한은 위의 **실제 사이트**, **남은 검증 범위·제한**을 그대로 적용합니다. 다수 SAF 제공자 사이의 중첩 폴더 이동과 모든 외부 동시 변경 조합은 별도 기기 검증 범위입니다.

## 다운로드 대기 표시·미디어 탭 수정 검증

`Runtime.emit()`이 350ms 이내의 이벤트를 버려, 실행기가 유휴 상태로 돌아간 뒤 마지막 완료 상태가 전달되지 않는 버그를 실기기에서 [수정 전 실패](test-results/queue-fix/snapshot-regression-before.txt)로 재현했습니다. 이제 변경을 모아 최신 스냅샷을 예약 전달합니다. 실제 네트워크·재시도·동시 실행 대기에는 이유를 표시하고 기존 재개 명령으로 다시 시작할 수 있습니다. 사용자의 네트워크 설정은 변경하지 않았습니다.

영상·오디오 탭의 이전 선택이 하나의 저장 요청에 섞이던 원인은 Flutter `selections`가 모든 형식을 열거했기 때문입니다. 현재 탭의 형식만 등록하며, 숨겨진 선택은 개수에 포함하지 않습니다. 탭별 화질 기억과 여러 이미지 선택은 유지합니다.

| 항목 | 이번 APK 결과·증거 |
| --- | --- |
| Flutter 분석·테스트 | [분석 오류 없음](test-results/queue-fix/flutter-analyze.txt), [25개 통과](test-results/queue-fix/flutter-test.txt). 오디오만 등록, 영상 탭 복귀 후 화질 보존·오디오 제외, 공유 수신, 복수 이미지, 대기 이유·재시작 명령 포함 |
| JVM | [네트워크 정책 5개](test-results/queue-fix/TEST-com.luverse.fetchy.NetworkPolicyTest.xml), [파일명 3개](test-results/queue-fix/TEST-com.luverse.fetchy.StorageNamesTest.xml) 통과 |
| Android 대기열 | [Android 12 실기기 4개](test-results/queue-fix/physical-native.txt), [API 35 에뮬레이터 4개](test-results/queue-fix/emulator-native.txt) 통과. 마지막 상태 전달 회귀, 개인정보 없는 진단, Activity 종료 후 영상·오디오 동시 완료, 일시정지·재개·취소·재시도 포함. 건너뛴 테스트 없음 |
| 실제 Flutter 저장 버튼 | 양쪽 기기에서 공개 직접 영상 URL을 분석하고 탭을 전환해 저장. 오디오·영상 각각 새 작업 **1개**, 완료·실제 파일 크기 확인: [실기기 오디오](test-results/queue-fix/physical-ui-audio.txt), [실기기 영상](test-results/queue-fix/physical-ui-video.txt), [에뮬레이터 오디오](test-results/queue-fix/emulator-ui-audio.txt), [에뮬레이터 영상](test-results/queue-fix/emulator-ui-video.txt) |
| 실제 화면 상태 | 새 분석·새로고침·앱 재시작 없이 다운로드 내역이 완료로 갱신됨: [실기기 오디오](test-results/queue-fix/physical-audio-completed.png), [실기기 영상](test-results/queue-fix/physical-video-completed.png), [API 35 오디오](test-results/queue-fix/emulator-audio-completed.png), [API 35 영상](test-results/queue-fix/emulator-video-completed.png). [오디오 선택 화면](test-results/queue-fix/physical-audio-selection.png)에 혼합 선택 안내 없음 |
| 프로세스·화면 종료 복구 | 실기기에서 [부분 다운로드 생성](test-results/queue-fix/physical-recovery-seed.txt) → 프로세스 강제 종료 → [화면을 끈 상태로 복구·저장 완료](test-results/queue-fix/physical-recovery-screen-off-restore.txt) → [fixture 정리](test-results/queue-fix/physical-recovery-cleanup.txt) 통과 |
| APK·정렬 | [release 빌드](test-results/queue-fix/release-build.txt), [Android 테스트 빌드](test-results/queue-fix/android-tests-build.txt) 성공. [ARM64·x86_64 ELF 520개 검사](test-results/queue-fix/native-alignment.json)에서 16KB 미만 없음 |

API 35 최초 UI 검증은 정상 알림 권한 화면에서 자동화가 기다리다 [시간 초과](test-results/queue-fix/emulator-ui-driver-notification-prompt.txt)했습니다. 권한 요청을 처리하고 첫 작업의 완료를 확인·정리한 뒤, [전체 UI 검증을 재실행](test-results/queue-fix/emulator-ui-driver.txt)해 통과했습니다. [실기기 UI 검증](test-results/queue-fix/physical-ui-driver.txt)도 통과했습니다. 테스트 helper는 시작 전 작업 ID·기존 최근 링크를 보관하고 새로 생성한 공개 fixture 작업·파일만 정리합니다.

마지막 [실기기 진단](test-results/queue-fix/physical-final-diagnostic.txt)·[에뮬레이터 진단](test-results/queue-fix/emulator-final-diagnostic.txt)에서 남은 작업이 없으며 Wi-Fi 전용·모바일 데이터 설정은 최초 진단과 동일합니다.

최종 파일은 [ARM64 APK](../artifacts/Fetchy-arm64.apk), [x86_64 APK](../artifacts/Fetchy-x86_64.apk), [SHA-256](../artifacts/SHA256SUMS.txt)입니다. 위 레퍼런스 개편의 로그는 이전 APK의 역사적 결과이고, 이 절의 로그가 이번 다운로드 수정 APK에 해당합니다. 기존 사이트별 제한과 미검증 범위는 유지됩니다. 조사 당시 연결된 실기기에는 기존 다운로드 작업이 없어, 사용자가 보고한 특정 링크 자체를 재현한 결과로 해석하지 않습니다.


## Android 16 다운로드 시작 경로 수정 (1.0.1+2)

Morit의 즉시 전송 실행·소유자 구분과 단일 영상 URL 분석 규칙을 확인했습니다. Fetchy는 UIDT 예약과 즉시 foreground service를 함께 시도하고, 실행 소유자 하나만 대기열을 처리합니다. 같은 예약의 반복 교체와 이전 실행기의 중지 콜백을 차단하고, 실행 예약을 알림 권한 요청보다 먼저 수행합니다. 예약 실패를 무시하던 앱 재개 경로도 오류를 기록합니다. 네트워크 요청에서는 암묵적인 NOT_VPN 제약을 제거했습니다.

사용자의 YouTube `watch` URL에 `list=RD…`가 포함돼 자동 믹스 목록으로 분석되던 문제도 수정했습니다. 현재 단일 영상으로 처리하며 명시적 재생목록과 Instagram 다중 자산 분석은 유지합니다. 참고한 실제 Morit 파일은 [코드 구조](architecture.md)에 연결했습니다. Morit 소스와 사용자의 네트워크 설정은 변경하지 않았습니다.

| 항목 | 결과·증거 |
| --- | --- |
| 시작 실패 회귀 | 테스트에서 Fetchy의 UIDT 서비스만 일시적으로 비활성화하면 [이전 APK는 예약 예외로 실패](test-results/dispatch-fix/unavailable-uidt-before.txt). 수정 APK는 [Android 16의 같은 상황에서 MP4 저장 완료](test-results/dispatch-fix/android16-native.txt). 이는 Samsung에서 해당 서비스가 비활성화되어 있었다는 증거는 아님 |
| Flutter | [분석 오류 없음](test-results/dispatch-fix/flutter-analyze.txt), [25개 테스트 통과](test-results/dispatch-fix/flutter-test.txt) |
| JVM | [네트워크 5개](test-results/dispatch-fix/TEST-com.luverse.fetchy.NetworkPolicyTest.xml), [이름 3개](test-results/dispatch-fix/TEST-com.luverse.fetchy.StorageNamesTest.xml), [YouTube URL/목록 구분 2개](test-results/dispatch-fix/TEST-com.luverse.fetchy.MediaUrlTest.xml) 통과 |
| Android 12 대기열 | [기본 렌더링 6개](test-results/dispatch-fix/physical-native-final.txt), [CPU 렌더링 옵션으로 동일 6개](test-results/dispatch-fix/physical-software-native.txt) 통과. 실행 소유자, 반복 예약, 동시 영상·오디오, 제어, 최종 스냅샷 포함. 중복 실행 횟수를 고유 테스트 수로 합산하지 않음 |
| 사용자 YouTube 링크 `5Mu9i_B8bjA` | Android 12 [실기기](test-results/dispatch-fix/physical-youtube-native.txt)와 Android 16 [에뮬레이터](test-results/dispatch-fix/android16-youtube-native.txt)에서 **자산 1개**, MP4 6,223,126 bytes 저장 완료. 원래 `list`·`start_radio` 쿼리 포함 입력으로 실행 |
| 사용자 릴스 `DaupWjVARQ_` | Android 12 [실기기](test-results/dispatch-fix/physical-instagram-native.txt)와 Android 16 [에뮬레이터](test-results/dispatch-fix/android16-instagram-native.txt)에서 MP4 975,495 bytes 저장 완료. 제공된 공유 URL을 그대로 입력 |
| 실제 Flutter 탭/저장 버튼 | 실기기 YouTube [영상 1개·MP4](test-results/dispatch-fix/physical-youtube-ui-video.txt), [오디오 1개·M4A](test-results/dispatch-fix/physical-youtube-audio-ui-audio.txt), [릴스 영상 1개](test-results/dispatch-fix/physical-instagram-ui-video.txt) 완료. Android 16에서도 [릴스 영상 1개](test-results/dispatch-fix/android16-instagram-ui-video.txt) 완료. 선택 개수 혼합 안내 없음. 실제 저장 MIME·파일 크기를 검사 |
| 완료 화면 | 새로고침 없이 [실기기 YouTube 영상](test-results/dispatch-fix/physical-youtube-video-completed.png)·[오디오](test-results/dispatch-fix/physical-youtube-audio-audio-completed.png), [실기기 릴스](test-results/dispatch-fix/physical-instagram-video-completed.png), [Android 16 릴스](test-results/dispatch-fix/android16-instagram-video-completed.png)에 완료 표시 |
| 화면 꺼짐·재시작 복구 | 실기기 [부분 파일 생성](test-results/dispatch-fix/physical-recovery-seed.txt) → 실제 프로세스 강제 종료 → [화면을 끈 채 복구·저장 완료](test-results/dispatch-fix/physical-recovery-screenoff.txt) → [fixture 정리](test-results/dispatch-fix/physical-recovery-cleanup.txt) 통과 |
| APK | [release 빌드](test-results/dispatch-fix/release-build.txt), [최종 테스트 APK 빌드](test-results/dispatch-fix/android-tests-final-build.txt) 성공. [패키지/버전/ABI](test-results/dispatch-fix/apk-manifest.json): `com.luverse.fetchy`, 1.0.1, ARM64 code 2002·x86_64 code 4002. [release와 산출물 Dex 일치](test-results/dispatch-fix/native-dex-consistency.json), [ELF 520개 16KB 정렬](test-results/dispatch-fix/native-alignment.json) 확인 |
| 서버 의존 | [Flutter·Kotlin main 소스 검사](test-results/dispatch-fix/server-dependency-scan.json)에 Morit 채널/백엔드·Supabase·Firebase·Cobalt 참조 없음. 전체 네트워크 패킷 캡처 결과를 의미하지 않음 |

Android 16 최초 대기열 조합 실행에서 7개 중 소유자 테스트 1개가 실패했습니다. 이전 전송이 완료된 뒤 서비스의 다음 idle 콜백이 아직 실행되지 않은 테스트 선행 조건 문제였습니다. [초기 로그](test-results/dispatch-fix/android16-native-owner-fixture-race.txt)를 보존하며, idle 해제를 기다리도록 고쳐 실기기에서 기본/CPU 렌더링 모두 재검증했습니다. Android 16 재실행은 [소프트웨어 렌더러 충돌로 중단](test-results/dispatch-fix/android16-native-final.txt)되었습니다. 반복적인 네이티브 테스트에 사용할 CPU 렌더링 인자를 [빌드 문서](build.md)에 기록했습니다.

워크스테이션은 Ryzen 5 5600G·RAM 16GB·내장 GPU입니다. [전용 실행 스크립트](../scripts/emulator_safe.sh)는 한 대만 실행하고 CPU·메모리·스왑·메모리 압력을 제한하며 빌드와 실행을 직렬화합니다. [중복 시작 차단](test-results/dispatch-fix/emulator-single-instance-check.txt), [시작 여유 부족 차단](test-results/dispatch-fix/emulator-headroom-check.txt), 실제 압력 상승 시 자동 종료를 확인했습니다. 렌더러 충돌은 컴퓨터 전체 OOM이나 과거 마우스 정지의 원인으로 단정하지 않습니다. [사양·제한·관찰 기록](emulator-workstation.md)을 참고하세요.

연결된 실기기는 **Silver Max Pro 2 / Android 12**이며 Android 16은 전용 Google APIs 에뮬레이터입니다. 사용자 **S22+의 One UI/절전 정책과 당시 멈춘 작업 로그는 직접 검증하지 못했습니다**. 위 두 링크의 실제 성공과 Samsung 기기 자체의 재검증을 구분합니다. 이전 일반 제한은 유지합니다.

새 설치 파일: [ARM64 APK](../artifacts/Fetchy-arm64.apk) · [x86_64 APK](../artifacts/Fetchy-x86_64.apk) · [SHA-256](../artifacts/SHA256SUMS.txt). 이 절은 1.0.1+2 산출물의 기록입니다. 최신 배포 검증은 아래 X 접근·공유 속도 절에 기록합니다.

## X 공개 접근·공유 로딩 개선 — 1.0.2+3

Morit의 FxTwitter 우선 분석과 syndication 대체 경로를 확인한 뒤, 사용자의 방식 선택 위임에 따라 **X 공개 메타데이터만 외부 FxTwitter API로 조회**합니다. 쿠키는 보내지 않으며 다운로드·변환·저장은 기기에서 처리합니다. 기존 로컬 분석 요구에서 달라진 네트워크 범위는 [아키텍처](architecture.md#공개-미디어-접근과-공유-분석)에 명시했습니다. 모든 추출기 오류를 로그인 문제로 표시하던 처리를 원인별로 구분했습니다.

### 실제 공유 화면 시간

Silver Max Pro 2 / Android 12에서 SEND Intent 실행부터 저장 버튼 표시까지 측정했습니다. 매번 앱 프로세스를 종료하고 다시 열었습니다. 최종 빌드의 첫 분석에는 새로운 `fetchy_benchmark=final` 쿼리를 붙여 기존 캐시를 피했고, 바로 같은 URL을 재공유해 Room 캐시를 확인했습니다. 같은 게시물을 사용했으며 UI 조회 도구의 대기 시간을 포함한 각 1회 관측입니다. 평균·최악 지연 측정은 아닙니다.

| 게시물 | 수정 전 | 최종 첫 분석 | 재시작 후 캐시 사용 |
| --- | --- | --- | --- |
| X `643211948184596480` | [28.0초 후 접근 실패](test-results/access-speed/share-x-before.json) | [3.8초, 선택 화면 표시](test-results/access-speed/share-x-cold-final.json) | [3.7초](test-results/access-speed/share-x-warm-final.json) |
| YouTube `5Mu9i_B8bjA` | [29.0초](test-results/access-speed/share-youtube-before.json) | [20.9초](test-results/access-speed/share-youtube-cold-final.json) | [3.5초](test-results/access-speed/share-youtube-warm-final.json) |
| Instagram `DaupWjVARQ_` | [26.3초](test-results/access-speed/share-instagram-before.json) | [18.3초](test-results/access-speed/share-instagram-cold-final.json) | [3.4초](test-results/access-speed/share-instagram-warm-final.json) |

최종 APK의 X 분석 자체는 아래 7개 실기기 테스트에서 **410~928ms**, 새 Engine 인스턴스의 캐시 조회는 **7~11ms**였습니다. 화면 표시 시간과 네이티브 분석 시간을 구분합니다. [최종 분석 로그](test-results/access-speed/final-analysis-timings.txt), [측정 스크립트](../scripts/benchmark_share.py)를 제공합니다. 느린 Instagram 공유를 닫은 뒤 다른 X 링크는 [4.0초에 열림](test-results/access-speed/share-after-cancel.json)을 확인했습니다.

### 최종 빌드 테스트

| 항목 | 결과·증거 |
| --- | --- |
| Flutter | [analyze 오류 없음](test-results/access-speed/flutter-analyze.txt), [26개 테스트 통과](test-results/access-speed/flutter-test.txt). 공유 분석을 스냅샷보다 먼저 시작하면서 저장된 화질을 지키는 회귀 테스트 추가 |
| JVM | [URL 규칙 2개](test-results/access-speed/TEST-com.luverse.fetchy.MediaUrlTest.xml), [네트워크 5개](test-results/access-speed/TEST-com.luverse.fetchy.NetworkPolicyTest.xml), [파일명 3개](test-results/access-speed/TEST-com.luverse.fetchy.StorageNamesTest.xml) 통과 |
| X 파서·취소 | [2개 통과](test-results/access-speed/native-parser-cancel.txt). 민감 플래그, 원본/미리보기 구분, 외부 CDN 거부, 안정적인 선택 ID, avc1 화질, `/photo/N`, 실행 전 취소 확인 |
| 공개 민감 영상·오디오 | `643211948184596480`: [MP4 629,101 B](test-results/access-speed/x-sensitive-video.txt), [M4A 210,013 B](test-results/access-speed/x-sensitive-audio.txt) 다운로드·ffprobe 검증. X 쿠키 미등록 상태를 테스트 시작 시 명시적으로 확인 |
| 공개 민감 GIF | `1575199173472927762`: [MP4 175,447 B](test-results/access-speed/x-sensitive-gif.txt). GIF에는 오디오 형식을 추가하지 않음 |
| 다중 미디어 | `1577719286659006464`: [자산 4개 분석 및 원본 다운로드](test-results/access-speed/x-four-videos.txt), [화면 종료 후 작업 4개 전부 완료·SAF 저장, 합계 521,660 B](test-results/access-speed/queue-x-four-videos.txt) |
| 실제 원본 이미지 | NASA `620951635783774208`: [PNG 447,894 B](test-results/access-speed/x-original-photo.txt). 썸네일 대신 `pbs.twimg.com/media/…?name=orig` 저장 |
| 최신 영상 경로 | `2001950365332455490`: [MP4 853,677 B](test-results/access-speed/x-current-codec.txt). 선택한 화질의 높이와 실제 ffprobe 높이 일치 확인. 카드 미리보기는 원본 이미지로 다운로드하지 않음 |
| 만료/삭제 CDN 주소 복구 | 유효한 CDN 경로의 파일명을 바꿔 실제 404 응답을 재현. [같은 자산·포맷으로 재분석 후 MP4 완료](test-results/access-speed/x-expired-url.txt), 원래 선택 객체의 검증 메타데이터 반영 확인 |
| 백그라운드 회귀 | [반복 예약·이전 소유자 중지 2개 통과](test-results/access-speed/queue-regressions.txt). [제공한 YouTube 링크 MP4 6,223,126 B](test-results/access-speed/queue-youtube.txt), [릴스 MP4 975,495 B](test-results/access-speed/queue-instagram.txt) 실제 대기열·화면 종료·저장 완료 |
| 빌드·호환성 | [Android 테스트 APK/JVM 빌드](test-results/access-speed/native-build.txt), [ARM64/x86_64 release 빌드](test-results/access-speed/release-build.txt) 성공. [ELF 520개 전부 16KiB 이상 정렬](test-results/access-speed/native-alignment.json). [최종 APK 해시](test-results/access-speed/apk-sha256.txt) |
| 네트워크 범위 | [소스 검사](test-results/access-speed/network-source-scan.json): Morit 백엔드·계정·Firebase·Supabase·Cobalt 참조 없음. X 보조 API 예외를 별도로 기록. 패킷 전체 캡처 결과는 아님 |

최종 실기기 테스트는 **14개 전부 통과**했습니다. 초기 파서 테스트의 [debug/release 메서드 바인딩 실패](test-results/access-speed/native-parser-cancel-before-binding-fix.txt)는 JVM 이름을 고정해 수정했습니다. 첫 [URL 복구 테스트](test-results/access-speed/x-expired-url-before-fixture-fix.txt)는 잘못된 CDN 루트 경로가 400을 반환해 실패했으며, 실제 404를 반환하는 원본 경로로 고쳐 재검증했습니다. 이 두 초기 로그는 최종 실패를 의미하지 않습니다.

### 남은 범위

새 YouTube·Instagram 링크의 첫 분석은 이 기기에서 약 21초·18초였습니다. 캐시는 동일 URL에 2분 적용되며 쿠키 변경·정리 시 무효화됩니다. 삭제·비공개·지역/로그인 제한과 보조 API 장애까지 모두 해제한다고 보장하지 않습니다. 사용자가 문제 링크 대신 공개 테스트 선정 자체를 위임했으므로, 사용자의 특정 X 게시물 성공을 검증한 결과는 아닙니다.

이번 수정은 Android 12 실기기에서 실행했습니다. PC 가용 메모리가 약 5GiB로 에뮬레이터 시작 기준 6GiB보다 낮아 [에뮬레이터를 종료 상태로 유지](test-results/access-speed/emulator-status.txt)했습니다. S22+ Android 16의 제조사 절전 정책과 이번 APK의 Android 16 실행은 재검증하지 않았습니다. 기존 Android 16 검증·렌더러 제한 기록은 앞 절을 참고하세요. Morit의 기존 변경 사항은 그대로 보존했습니다.

## 플랫폼 공통 미리보기·전송 개선 — 1.0.3+4

화질 분석이 끝날 때까지 제목·이미지도 표시하지 않던 경로를 분리했습니다. 공개 메타데이터는 Python 초기화·다운로드 대기열과 독립적으로 조회합니다. 공통 엔진은 동일 번들을 모듈로 설치해 Python 컴파일 결과를 재사용하고, 플레이어 처리·짧은 수명의 해석 결과도 재사용합니다. 가능한 원본은 HTTP로 전송하며, YouTube·TikTok의 세션·요청 처리는 yt-dlp에 유지합니다. 저장·검증·취소·정확한 포맷 선택은 유지했습니다. 구현·캐시 한도는 [아키텍처](architecture.md#103-미리보기전송-경로)에 기록합니다.

### 실제 공유 화면: 제목·이미지와 화질 선택을 구분

Silver Max Pro 2 / Android 12, 최종 ARM64 release APK, 실제 Wi-Fi에서 측정했습니다. 첫 요청마다 공개 미리보기·분석·플레이어 캐시를 비우고 앱 프로세스를 종료했습니다. 설치된 엔진 모듈의 바이트코드는 유지합니다. 재공유도 프로세스를 종료하되 결과 캐시는 유지했습니다. 아래 시간은 Flutter 요청 시작부터 실제 프레임/이미지 디코딩 후 프레임까지의 각 1회 관측이며 평균·최악 지연이 아닙니다. Android Activity 시작 시간은 포함하지 않습니다.

| 공개 링크 | 첫 제목/미리보기 프레임 | 첫 이미지 표시 | 첫 화질 선택 준비 | 재공유 이미지 / 선택 |
| --- | --- | --- | --- | --- |
| YouTube `5Mu9i_B8bjA` | 0.562초 | **1.081초** | 9.556초 | 0.682 / 0.295초 ([첫 요청](test-results/preview-speed/ui-youtube-cold.json), [재공유](test-results/preview-speed/ui-youtube-warm.json)) |
| Instagram `DaupWjVARQ_` | 1.119초¹ | **1.560초** | 1.119초 | 0.648 / 0.228초 ([첫 요청](test-results/preview-speed/ui-reel-cold.json), [재공유](test-results/preview-speed/ui-reel-warm.json)) |
| TikTok `7059698374567611694` | 0.712초 | **1.383초** | 9.313초 | 0.825 / 0.340초 ([첫 요청](test-results/preview-speed/ui-tiktok-cold.json), [재공유](test-results/preview-speed/ui-tiktok-warm.json)) |
| X `643211948184596480` | 0.728초¹ | **1.191초** | 0.728초 | 0.700 / 0.246초 ([첫 요청](test-results/preview-speed/ui-x-cold.json), [재공유](test-results/preview-speed/ui-x-warm.json)) |

¹ 전체 분석이 먼저 완료해 제목과 선택 화면을 함께 표시했습니다. `previewFrameMs`가 없는 경우 `selectionFrameMs`를 사용합니다. 측정 로그의 `elapsedSeconds`는 접근성 조회가 더한 약 2~3초를 포함하므로 이미지 지연과 혼동하지 않습니다. [벤치마크 스크립트](../scripts/benchmark_share.py)는 메타데이터/선택/실제 이미지 프레임을 각각 기록합니다. [기존 1.0.2 측정](#실제-공유-화면-시간)과 이번 프레임 측정은 방법이 달라 단순 배율 비교를 하지 않습니다.

### 네이티브 분석·실제 파일 전송

공개 캐시를 비운 뒤 미리보기, 전체 분석, 새 Engine의 미리보기 캐시 조회, 선택한 파일 전송·ffprobe 검증을 순서대로 수행했습니다. 전송 시간에는 필요한 변환·병합·검증을 포함하고 SAF 게시 시간은 포함하지 않습니다. 아래 포맷은 측정용 소형 영상을 선택한 결과이며 모든 화질의 전송 시간을 의미하지 않습니다.

| 경로 | 미리보기 / 분석 | 첫 전송 바이트 / 완료 | 실제 결과·로그 |
| --- | --- | --- | --- |
| YouTube | 0.768 / 9.733초 | 4.748 / 14.324초 | MP4 3,937,911 B, 실제 형식 52개, 해석 결과 재사용 ([로그](test-results/preview-speed/native-youtube.txt)) |
| Instagram 원본 | 0.744 / 0.789초 | 0.076 / 2.489초 | MP4 6,379,840 B, 영상 원본 + 오디오 출력 3개 ([로그](test-results/preview-speed/native-reel.txt)) |
| Instagram 오디오 | 1.097 / 0.689초 | 0.060 / 6.301초 | M4A 633,334 B, 실제 원본에서 추출 ([로그](test-results/preview-speed/native-reel-audio.txt)) |
| Instagram 추가 화질 | 0.796 / 7.214초 | 0.088 / 2.396초 | 실제 형식 13개, MP4 975,495 B ([로그](test-results/preview-speed/native-reel-full-formats.txt)) |
| TikTok | 0.908 / 8.396초 | 7.152 / 9.602초 | 실제 형식 7개, MP4 658,136 B, 익명 세션 포함 기존 전송 ([로그](test-results/preview-speed/native-tiktok.txt)) |
| X | 0.496 / 0.308초 | 0.474 / 2.069초 | 실제 형식 5개, MP4 629,101 B, X 쿠키 미등록 확인 ([로그](test-results/preview-speed/native-x.txt)) |

별도 공개 미리보기는 [Vimeo 1.161초](test-results/preview-speed/preview-vimeo.txt), [TikTok 0.412초](test-results/preview-speed/preview-tiktok.txt), [직접 JPEG 0.030초](test-results/preview-speed/preview-image.txt)를 확인했습니다. 재시작 후 메타데이터 조회는 주요 플랫폼에서 8~15ms였습니다. 설치된 Python 모듈은 [73개 바이트코드 생성과 새 Engine 초기화 2.604/2.220초](test-results/preview-speed/python-startup-after.txt)를 확인했습니다. [기존 초기화 검사 7.033초](test-results/preview-speed/python-startup-before.txt)와 검사 구성이 다르므로 순수 import 시간의 정확한 배율로 해석하지 않습니다.

### 최종 빌드 회귀 검사

| 항목 | 결과·증거 |
| --- | --- |
| Flutter | [analyze 오류 없음](test-results/preview-speed/flutter-analyze.txt), [32개 통과](test-results/preview-speed/flutter-test.txt). 조기 미리보기만으로 저장 금지, 10초 이후 대기 UI, 뒤로 가기/늦은 응답, 직접 형식 기본 선택·수동 선택 유지, 추가 화질 회귀 포함 |
| JVM | 총 13개 통과: [URL 2개](test-results/preview-speed/TEST-com.luverse.fetchy.MediaUrlTest.xml), [미리보기 URL 3개](test-results/preview-speed/TEST-com.luverse.fetchy.PreviewUrlTest.xml), [네트워크 5개](test-results/preview-speed/TEST-com.luverse.fetchy.NetworkPolicyTest.xml), [파일명 3개](test-results/preview-speed/TEST-com.luverse.fetchy.StorageNamesTest.xml) |
| 실기기 | 최종 다운로드/분석 suite의 **23개 전부 통과**. [파서·취소 4개](test-results/preview-speed/parser-cancel.txt), 위 실제 플랫폼·초기화·미리보기 검사 포함. 공유 화면 8회도 모두 선택 화면 표시 |
| 해석 결과·URL 만료 | [해석 JSON 사용 전송](test-results/preview-speed/resolved-info-fallback.txt), [실제 404를 만든 뒤 정확한 포맷으로 원본 재추출](test-results/preview-speed/resolved-info-expiry.txt), [X 만료 원본 재조회](test-results/preview-speed/x-expiry.txt) 통과 |
| 부분 스트림 복구 | [영상/음성 스트림 다운로드 중 취소 → 부분 파일 보존 → 새 Engine의 이어받기·병합·검증](test-results/preview-speed/resume-streams.txt) 통과 |
| 실제 대기열·SAF | [소유자/반복 예약 2개](test-results/preview-speed/queue-regression.txt), 화면 종료 후 [YouTube](test-results/preview-speed/queue-youtube.txt), [릴스 원본](test-results/preview-speed/queue-reel.txt), [X 미디어 4개](test-results/preview-speed/queue-x-multi.txt) 전부 저장 완료. 테스트가 만든 파일·작업만 정리 |
| 빌드·ABI | [Android 테스트 APK·JVM 빌드](test-results/preview-speed/native-build-tests.txt), [ARM64/x86_64 release 빌드](test-results/preview-speed/release-build.txt), [최종 SHA-256](test-results/preview-speed/apk-sha256.txt), [ELF 520개 16KiB 이상 정렬](test-results/preview-speed/native-alignment.json) 확인 |
| 네트워크 범위 | [소스 검사](test-results/preview-speed/network-source-scan.json): Morit 백엔드·Supabase·Firebase·Cobalt 참조 없음. 공식 사이트 메타데이터와 기존 X 보조 API 사용. 패킷 캡처 결과는 아님 |

실행 예: [벤치마크 스크립트](../scripts/benchmark_share.py)에 `--serial MTK0000903071707360 --url '공개 URL' --out 결과.json --cold-process`를 전달합니다. 공개 캐시 초기화는 테스트 APK의 `PreviewDeviceTest#clearPerformanceCaches`로 별도 수행합니다. [실기기 테스트 소스](../android/app/src/androidTest/kotlin/com/luverse/fetchy/PreviewDeviceTest.kt)의 `livePreviewAndExactDownload`는 `mediaUrl`, `mediaKind`, `fullFormats`, `forceExtractorDownload`, `expireResolved` 인자를 지원합니다. 자동 테스트 로그에는 쿠키·원본 인증 헤더를 출력하지 않습니다.

### 제한과 실행 환경

- 위 4개 공유 사례는 **실제 이미지 2초 이내**, 화질 선택 10초 이내였습니다. 전 세계 네트워크·모든 게시물에 대한 보장은 아닙니다. Python 최초 모듈 설치, 플레이어 변경, 지역·로그인 제한, CDN 지연에는 추가 시간이 필요할 수 있습니다.
- 10초 지연 시 스피너를 다시 시도·원본 열기로 바꾸지만 실제 추출은 계속합니다. 미리보기 성공을 다운로드 성공이나 존재하지 않는 형식으로 표시하지 않습니다. 직접 영상/음성 URL에는 웹 썸네일이 없으면 파일명만 먼저 표시합니다.
- Instagram 빠른 경로와 전체 엔진은 서로 다른 원본/화질을 반환할 수 있습니다. 위 6.38MB 원본과 이전 0.98MB 영상은 동일 파일 전송 속도의 비교 대상이 아닙니다. 원본의 표시용 dimensions를 실제 영상 해상도로 추측하지 않습니다.
- HTML·임베드가 원본을 제공하지 않으면 기존 전체 엔진으로 돌아갑니다. Facebook·Reddit·Pinterest 등 모든 사이트의 모든 링크를 실기기로 열어 검증한 결과는 아닙니다. DRM·삭제·비공개·서비스 차단은 기존 제한을 유지합니다.
- 이번 APK는 Android 12 실기기에서 검증했습니다. [가용 RAM 약 5GiB·스왑 부족](test-results/preview-speed/emulator-status.json)으로 에뮬레이터는 실행하지 않았습니다. 사용자 S22+ Android 16과 실제 16KB 페이지 하드웨어는 이번 버전에서 미검증입니다. Morit 코드는 변경하지 않았습니다.

## X 다운로드·ZIP 설치 오류 수정 — 1.0.4+5

사용자가 S22+ Android 16에서 보고한 오류는 `Entry at offset zero has invalid LFH signature 752f2123`입니다. APK의 원본 yt-dlp 첫 4바이트는 `#!/u`이며, little endian으로 읽으면 정확히 `752f2123`입니다. [Android ZipFile의 첫 헤더 검사](https://android.googlesource.com/platform/libcore/+/744c095ffc6c0b4bb2ca3853c5eb841146011619%5E2..744c095ffc6c0b4bb2ca3853c5eb841146011619/)와 일치합니다. X 공개 분석은 Python 설치를 거치지 않지만 다운로드 후 검증에서는 `Engine.initialize`를 호출하므로, 미리보기 성공 후 이 설치 예외로 실패할 수 있었습니다.

`BundledPython.copyZipPayload`가 23바이트 실행문을 제거한 표준 ZIP 본문을 설치하도록 수정했습니다. 본문의 중앙 디렉터리와 엔트리 offset은 이미 본문 기준이어서 추가 변경이 필요하지 않습니다. `modules-v3` 마커로 이전/실패 설치를 복구하며, 원본 번들 SHA·Python/yt-dlp 버전·사용자 작업/파일/쿠키/설정은 유지합니다. APK 설치 후 기존 실패 항목에서 **다시 시도**를 사용할 수 있습니다. [바이트·offset 진단](test-results/x-download-fix/zip-header-diagnosis.json), [설치 구조](architecture.md#104-zip-헤더-호환설치-복구)를 제공합니다.

조사 중 확인한 `X (Twitter)` 분석 이름도 실제 `X` 플랫폼 폴더와 연결했습니다. 사용자 폴더 ID·이름 변경·삭제 매핑을 유지하고, 기존 저장 파일을 자동 이동하지 않습니다.

| 항목 | 최종 결과·증거 |
| --- | --- |
| Android ZIP 읽기 도구 | [SDK 36 `aapt list`](test-results/x-download-fix/strict-android-zip-parser.json): 원본 실행 파일은 거부, 수정된 ZIP 본문은 **1,053개 엔트리 정상 조회**. 호스트 도구 검사이며 Android 16 앱 실행은 아님 |
| 실제 플랫폼 ZIP·설치 복구 | [기기 테스트 2개](test-results/x-download-fix/bundle-install.txt): 실제 asset의 실행문 제거·엔트리 읽기, 이전/중단 설치 복구, 재호출 시 바이트코드 보존 |
| 공통 엔진 | [초기화 통과](test-results/x-download-fix/engine-ready.txt), [새 Engine 2.295/2.228초·73개 바이트코드 확인](test-results/x-download-fix/engine-bytecode.txt) |
| Flutter | [analyze 오류 없음](test-results/x-download-fix/flutter-analyze.txt), [33개 통과](test-results/x-download-fix/flutter-test.txt). X 분석 이름과 실제 플랫폼 폴더 표시 회귀 추가 |
| JVM | 총 **17개 통과**: [실제 ZIP asset/일반 ZIP/잘못된 실행문 4개](test-results/x-download-fix/TEST-com.luverse.fetchy.BundledPythonTest.xml), [URL 2개](test-results/x-download-fix/TEST-com.luverse.fetchy.MediaUrlTest.xml), [미리보기 URL 3개](test-results/x-download-fix/TEST-com.luverse.fetchy.PreviewUrlTest.xml), [네트워크 5개](test-results/x-download-fix/TEST-com.luverse.fetchy.NetworkPolicyTest.xml), [파일명 3개](test-results/x-download-fix/TEST-com.luverse.fetchy.StorageNamesTest.xml) |
| X 실제 원본 | 쿠키 미등록 상태에서 [영상 MP4 629,101 B](test-results/x-download-fix/x-video.txt), [오디오 M4A 210,013 B](test-results/x-download-fix/x-audio.txt), [사진 PNG 447,894 B](test-results/x-download-fix/x-photo.txt), [GIF의 MP4 175,447 B](test-results/x-download-fix/x-gif.txt), [최신 avc1 경로 MP4 853,677 B](test-results/x-download-fix/x-current-codec.txt) 전송·ffprobe·무결성/디코딩 검증 통과 |
| 만료·선택 유지 | [실제 404 원본 → 재분석 → 동일 자산·형식 MP4 완료](test-results/x-download-fix/x-expiry.txt), [X 파서/예약 취소 2개](test-results/x-download-fix/parser-cancel.txt) 통과 |
| 실패 항목 재시도·저장 | [LFH 오류로 실패한 상태를 저장 → 같은 ID/포맷으로 retry → 화면 종료 후 SAF 완료](test-results/x-download-fix/queue-x-retry.txt), MP4 1,664,010 B. [사진 SAF 저장](test-results/x-download-fix/queue-x-photo.txt), [X 원본 4개 저장](test-results/x-download-fix/queue-x-multi.txt) 통과 |
| 실제 Flutter 저장 버튼 | [오디오 탭 M4A 1개·210,013 B](test-results/x-download-fix/final-x-ui-ui-audio.txt), [영상 탭 MP4 1개·1,664,010 B](test-results/x-download-fix/final-x-ui-ui-video.txt) 완료. 접근성 클릭 → 실제 Room 작업/파일/MIME 검증. [오디오 완료 화면](test-results/x-download-fix/final-x-ui-audio-completed.png)·[영상 완료 화면](test-results/x-download-fix/final-x-ui-video-completed.png) 확인, [fixture 정리](test-results/x-download-fix/final-ui-run.txt) 완료 |
| 기존 기능 회귀 | [소유자/반복 예약 2개](test-results/x-download-fix/queue-regression.txt), 제공한 [YouTube](test-results/x-download-fix/queue-youtube.txt)·[릴스](test-results/x-download-fix/queue-reel.txt) 실제 백그라운드 전송·SAF 저장 통과. [X 폴더 별칭](test-results/x-download-fix/x-folder-alias.txt) 검사에서 ID·사용자 설정 유지 |
| 빌드·ABI | [release](test-results/x-download-fix/release-build.txt), [최종 테스트 APK/JVM 빌드](test-results/x-download-fix/native-build-tests-final.txt), [버전·ABI](test-results/x-download-fix/apk-manifest.json), [SHA-256](test-results/x-download-fix/apk-sha256.txt), [ELF 520개 모두 16KiB 이상 정렬](test-results/x-download-fix/native-alignment.json) 확인 |
| 네트워크 | [소스 검사](test-results/x-download-fix/network-source-scan.json): Morit 백엔드·Supabase·Firebase·Cobalt 참조 없음. 기존 공개 X 보조 분석·원본 CDN 경로 유지. 패킷 캡처 결과는 아님 |

최종 ARM64 release APK를 설치한 Silver Max Pro 2 / Android 12의 위 네이티브 suite는 **20개 통과**했습니다. 테스트가 생성한 작업·파일만 정리합니다. 수정 전에도 이 기기의 [영상](test-results/x-download-fix/before-video.txt)·[사진](test-results/x-download-fix/before-photo.txt)·[새 코덱](test-results/x-download-fix/before-current-codec.txt)·[GIF](test-results/x-download-fix/before-gif.txt)는 성공했으므로, Android 12 성공만으로 최신 Android의 헤더 검사를 검증했다고 보지 않습니다. 원본 실행문과 Android 검사 구현, 표준 ZIP 본문/offset, 호스트·플랫폼 ZIP 읽기를 별도로 확인했습니다.

첫 UI 검사는 `uiautomator` 종료 코드 137로 중단됐으며 [준비한 fixture를 정리](test-results/x-download-fix/before-ui-fixture-cleanup.txt)하고 UI 조작을 직렬화해 위 두 저장을 재검증했습니다. 첫 [YouTube 배치](test-results/x-download-fix/intermediate/queue-youtube-interrupted.txt)는 ADB transport가 갱신된 시점에 종료 로그가 누락돼 통과로 집계하지 않았으며, 위 최종 로그는 재실행한 완료 결과입니다. 이러한 도구 중단을 X 다운로드 실패로 해석하지 않습니다. Samsung S22+ Android 16의 실제 재설치는 직접 수행하지 못했습니다. PC 가용 메모리/스왑 조건 때문에 에뮬레이터는 실행하지 않았으며 Morit 코드는 수정하지 않았습니다.


## Instagram 스토리 URL·쿠키 연결 — 1.0.5+6

스토리 주소는 `Engine.platform`의 Instagram 경로 목록에 없었습니다. 따라서 사용자가 로그인하고 쿠키를 저장해도 `execute → CookieVault.temporary`까지 도달하지 못하고 `Unsupported Instagram post URL`로 종료됐습니다. 스토리·하이라이트를 검증해 허용하고, 특정 스토리는 `--no-playlist`, 목록·하이라이트는 다중 자산으로 처리합니다. 모바일 호스트 정규화 시 쿼리는 보존합니다. 공개 게시물 임베드는 스토리에 요청하지 않습니다.

번들 `InstagramStoryIE`가 사진만 있는 항목을 제외하는 동작도 확인했습니다. 앱 내부의 작은 추출 확장은 명시적 `media_type=1` 사진 원본만 보존하며 영상 포스터를 제외합니다. 기존 인증 요청과 영상 추출·전송은 그대로 재사용하고, 네이티브 사진 파서는 원본 표식·형식 ID·Instagram CDN을 함께 검사합니다. 기존 쿠키·작업·파일·설정은 변경하지 않습니다.

| 검증 | 실제 결과 |
| --- | --- |
| Flutter | [analyze 오류 없음](test-results/instagram-story-fix/flutter-analyze.txt), [기존 위젯 33개 통과](test-results/instagram-story-fix/flutter-test.txt) |
| JVM | [19개 통과](test-results/instagram-story-fix/jvm-summary.json). 개별 스토리/계정 목록/하이라이트 구분, 잘못된 주소·외부 도메인 거부, 호스트 정규화·쿼리 및 ID 보존 |
| 저장된 쿠키 연결 | [실기기 검사](test-results/instagram-story-fix/native-story-final.txt). 사용자 저장소와 분리된 테스트 쿠키를 암호화 가져오기 → 임시 파일 → 실제 엔진 인자로 전달 → 실행 후 삭제. 실제 계정 로그인 성공 검사가 아님 |
| 제공한 스토리 링크 | 같은 [실기기 로그](test-results/instagram-story-fix/native-story-final.txt)에서 ID `4002710559245555959`가 URL 거부를 지나 추출기에 도달. 쿠키 없는 이 기기에서는 정상적인 접근/로그인 제한으로 종료; 다운로드 성공으로 집계하지 않음 |
| 사진·단일 선택 | [번들 CLI 검사](test-results/instagram-story-fix/bundled-story-check.txt), 같은 스크립트를 Android Python 3.12에서 실행한 [실기기 로그](test-results/instagram-story-fix/native-story-final.txt). **원격 응답은 모의 데이터**: 사진 최대 해상도 원본, 영상 포스터 제외, 특정 ID 1개, 계정 목록에서 사진+영상 2개 유지. 네이티브 파서의 원본/외부 호스트/표식 없는 포스터 검사도 통과 |
| 기존 Instagram 회귀 | [릴스 실제 전송·검증 및 임베드 파서 2개](test-results/instagram-story-fix/reel-regression-final.txt) 통과. MP4 **6,379,840 B**, 미리보기 698ms, 분석 502ms, 전송·후처리 2,349ms |
| 기존 YouTube 회귀 | [실제 미리보기·분석·정확한 형식 전송](test-results/instagram-story-fix/youtube-regression.txt) 통과. MP4 **3,937,911 B**, 미리보기 635ms, 분석 7,337ms, 전송·후처리 15,095ms |
| 산출물 | [release 빌드](test-results/instagram-story-fix/release-build-complete.txt), [테스트 APK/JVM 빌드](test-results/instagram-story-fix/tests-build-complete.txt), [버전·ABI·테스트 요약](test-results/instagram-story-fix/summary.json), [APK SHA-256](test-results/instagram-story-fix/apk-sha256.txt). yt-dlp 원본 asset SHA는 이전과 동일하며 별도 Fetchy 추출 확장이 두 APK에 포함됨 |

최종 ARM64 release를 설치한 Silver Max Pro 2 / Android 12에서 위 JUnit 검사는 **7개 통과**했습니다(스토리 경로/쿠키/파서/내장 Python 4개 + 릴스/파서 2개 + YouTube 1개). 스토리 사진 원본의 파싱·선택 검사는 모의 응답이며, **실제 로그인 계정으로 사진/영상 스토리를 저장한 검증은 수행하지 못했습니다**. 스토리 만료·계정의 보기 권한·Instagram의 추가 인증 응답은 별도 확인이 필요합니다. Samsung S22+ Android 16의 직접 실행도 이번 환경에서 수행하지 못했습니다. 새 버전에서 SAF 게시·공유·백그라운드 제어를 다시 검사한 것으로 표시하지 않으며 이전 버전 검증과 구분합니다. PC 부담을 줄이기 위해 에뮬레이터를 사용하지 않았고 Morit 소스는 수정하지 않았습니다.

첫 [목록 조회 검사](test-results/instagram-story-fix/intermediate/native-story-final.txt)는 `--list-extractors` 출력에 확장 이름을 기대한 assertion이 실패했습니다. 해당 목록 명령이 실제 추출의 확장 로드보다 먼저 끝나는 것을 확인했고, 최종 검사에서는 실제 CLI 분석 명령으로 확장 로드·원본 보존을 확인합니다. [호스트 도구의 run-as 시도](test-results/instagram-story-fix/intermediate/device-bundled-story-check.txt)는 release APK가 디버그 가능하지 않아 거부되어, 최종 내장 Python 검사를 instrumentation의 앱 컨텍스트에서 실행했습니다. 이를 앱의 미디어 접근 실패로 해석하지 않습니다. 테스트 평문·모의 응답 파일·다운로드 임시 출력은 정리하며 사용자 쿠키와 기록은 유지합니다.

재현: `python3 scripts/check_instagram_stories.py`. 내장 Python 검사는 `adb push scripts/check_instagram_stories.py /data/local/tmp/fetchy-story-check.py` 후 `PreviewDeviceTest#bundledStoryCheckOnAndroidPython`을 실행하며, 완료 후 그 임시 스크립트를 삭제합니다.

## 1.0.6 개별 플랫폼·미디어 확장 (2026-10-08)
전체/대량 다운로드를 추가하지 않고 개별 링크의 등록·원본 추출·쿠키 연결을 확장했습니다. 플랫폼 등록 29→43개, 암호화 쿠키 저장소 4→21개입니다. [지원 범위](platform-support.md)에 URL 연결과 실제 다운로드 성공을 구분합니다. 번들 yt-dlp·FFmpeg·QuickJS·Python 버전은 유지하며 Morit 코드는 수정하지 않았습니다.
### 코드와 모의 원본 응답 검사
| 검사 | 결과 |
| --- | --- |
| Flutter | [분석 오류 없음](test-results/platform-expansion/flutter-analyze.txt), [위젯 35개 통과](test-results/platform-expansion/flutter-test.txt). 새 플랫폼 메뉴·동적 쿠키 목록·사진 게시물 오디오 탭의 단독 선택 포함 |
| JVM | 23개 통과. [개별 주소·컬렉션 차단·쿠키 라우팅·원본 호스트](test-results/platform-expansion/TEST-com.luverse.fetchy.MediaUrlTest.xml), [토큰 보존·주소 별칭](test-results/platform-expansion/TEST-com.luverse.fetchy.PreviewUrlTest.xml), 기존 네트워크·파일명·번들 설치 회귀 |
| 실제 번들 CLI / 모의 원본 응답 | [TikTok·Pinterest·Reddit·Bluesky·Instagram 사진](test-results/platform-expansion/post-photos-cli.txt), [기존 스토리 회귀](test-results/platform-expansion/story-regression-cli.txt) 통과. 영상 포스터 제외·게시물 원본·음악 분리 확인 |
| Android 내장 Python·네이티브 | [파서·누락된 코덱 처리·확장 쿠키 격리·Python 3개](test-results/platform-expansion/native-fixtures.txt), [기존 스토리·게시물 4개](test-results/platform-expansion/story-fixtures.txt), [소유자/반복 예약 2개](test-results/platform-expansion/queue-regression.txt), [자체 중단 fixture 정리 1개](test-results/platform-expansion/fixture-cleanup.txt) 통과 |

### 실제 다운로드와 SAF 저장
Silver Max Pro 2 / Android 12에서 아래 **9개** 개별 다운로드를 완료했습니다. 실제 FFmpeg/ffprobe 검증과 SAF 게시 후 Content URI로 다시 읽어 원본 크기와 비교했습니다. 각 검사는 자신이 만든 파일·폴더·작업만 삭제했습니다.
| 플랫폼·미디어 | 분석 | 다운로드·검증·저장까지 | 실제 파일 |
| --- | --- | --- | --- |
| [Bluesky · image](https://bsky.app/profile/atproto.com/post/3mtk2loeuxk24) | 4.864초 | 8.339초 | [JPG 106,580 B](test-results/platform-expansion/bluesky-photo.txt) |
| [Streamable · video](https://streamable.com/dnd1) | 7.150초 | 14.950초 | [MP4 12,171,177 B](test-results/platform-expansion/streamable.txt) |
| [Naver TV · video](https://tv.naver.com/v/67838091) | 5.215초 | 16.955초 | [MP4 1,307,724 B](test-results/platform-expansion/naver-tv.txt) |
| [Bandcamp · audio](https://diskotopia.bandcamp.com/track/safehouse) | 4.910초 | 7.525초 | [MP3 2,564,272 B](test-results/platform-expansion/bandcamp.txt) |
| [TikTok · image](https://www.tiktok.com/@chillax_66/photo/7508442021644782866) | 4.507초 | 6.791초 | [JPG 15,884 B](test-results/platform-expansion/tiktok-photo.txt) |
| [YouTube · video](https://www.youtube.com/watch?v=jNQXAC9IVRw) | 6.593초 | 15.020초 | [MP4 509,329 B](test-results/platform-expansion/youtube-regression.txt) |
| [Instagram · video](https://www.instagram.com/reel/DaupWjVARQ_/) | 1.014초 | 4.529초 | [MP4 6,379,840 B](test-results/platform-expansion/instagram-regression.txt) |
| [X (Twitter) · video](https://x.com/i/status/643211948184596480) | 0.505초 | 3.298초 | [MP4 629,101 B](test-results/platform-expansion/x-regression.txt) |
| [Naver TV · audio](https://tv.naver.com/v/67838091) | 5.228초 | 17.458초 | [M4A 573,844 B](test-results/platform-expansion/naver-audio.txt) |

위 시간은 전체 분석/저장 시간이며 독립적인 미리보기 렌더 시간과 다릅니다. 이 버전에서 공유 미리보기 시간을 전 플랫폼에 다시 측정한 것으로 표시하지 않습니다.

### 실패·미검증
- [KakaoTV 테스트 링크](test-results/platform-expansion/kakao-tv.txt): 메타데이터는 반환되지만 원본 포맷이 없어 실패.
- [Rumble 테스트 링크](test-results/platform-expansion/rumble.txt): 원본 분석 실패. 동일 URL의 호스트 분석에서는 JSON API HTTP 403이 관찰됨.
- [Reddit 실제 사진 링크](test-results/platform-expansion/reddit-photo.txt): 원본 요청에 미디어 대신 페이지가 반환되어 검증 단계에서 실패. 모의 gallery/photo 파서 통과를 실제 저장 성공으로 집계하지 않음.
- 새 쿠키 저장소는 암호화·도메인 격리·임시 파일 정리를 검사했으며 21개 사이트에서 실제 계정 로그인을 수행한 것은 아님. Instagram의 로그인된 실제 스토리/하이라이트 저장, Pinterest 실제 사진 저장, 등록된 나머지 플랫폼의 실제 다운로드는 별도 검증 필요.
- Android 16 Samsung S22+는 연결되지 않아 직접 검증하지 못함. PC 부담을 줄이기 위해 에뮬레이터를 사용하지 않고 `build-safe`로 빌드를 직렬화함.

### 중간 검사와 최종 결과의 구분
첫 실기기 배치는 USB transport 변경으로 중단됐으며 [중간 로그](test-results/platform-expansion/intermediate-usb/device-summary.json)는 플랫폼 실패로 집계하지 않습니다. 다음 배치의 일부 파일은 다운로드/게시 후 테스트가 자체 `saving` 작업을 제거하기 전에 파일을 정리해 기존 활성 작업 보호에 막혔습니다. [중간 로그](test-results/platform-expansion/intermediate-cleanup/device-summary.json)를 보존하고 테스트 순서만 수정해 재검증했습니다. 제품의 활성 폴더 보호를 완화하지 않았습니다. Naver TV의 코덱 이름 누락은 공통 파서에서 실제 해상도/오디오 비트레이트로 처리하고 명시적인 `none`을 존중하는 검사도 추가했습니다.

논리 사례 기준 실기기 **19개 통과 / 위 공개 링크 3개 실패**이며 [결과 JSON](test-results/platform-expansion/device-summary.json)에 구분합니다. ARM64·x86_64 설치 APK 빌드와 원본 엔진/두 확장 asset 일치는 [빌드 로그](test-results/platform-expansion/release-build.txt), [APK 해시](test-results/platform-expansion/apk-assets.json)에 기록합니다.

최종 APK에는 KakaoTV `play-tv` 호스트 별칭도 포함하고 JVM 검사를 재실행했습니다. 최종 ARM64 APK를 다시 설치해 [원본/코덱 파서·암호화 쿠키 2개](test-results/platform-expansion/final-apk-fixtures.txt)와 [Naver TV 오디오 실제 저장 1개](test-results/platform-expansion/final-apk-audio.txt)를 재확인했습니다. 이는 위 논리 사례의 반복 검사이며 성공 사례 수에 중복 합산하지 않습니다. 최종 APK의 원본 엔진·두 확장 source asset은 모두 일치하며 문서의 실제 로컬 경로 360개에 누락이 없었습니다.

## 자동 저장 위치·외부 삭제 기록 정리 (1.0.7+8)

저장 위치가 비어 있으면 `Android/media/com.luverse.fetchy/Fetchy`를 자동 생성합니다. 기존 위치·권한·파일 URI는 유지하며, 설정과 파일 메뉴의 위치 변경은 새 다운로드부터 적용합니다. 기본 위치는 앱 제거 시 함께 삭제되므로 설정에 표시합니다. 자동 폴더의 Content URI·폴더 계층·게시 검증은 기존 SAF 파이프라인을 재사용합니다.

파일 삭제는 유효한 URI를 먼저 처리하고, 빈 조회/검증 예외는 실제 상위 폴더 목록으로 부재를 확인합니다. 접근 불가를 삭제로 취급하지 않습니다. Android는 없는 문서와 사용할 수 없는 제공자 모두에 `null`을 반환할 수 있어 단일 조회의 결과만으로 기록을 지우지 않습니다.

| 검증 | 최종 결과·증거 |
| --- | --- |
| Flutter 분석 | [오류 없음](test-results/automatic-storage/flutter-analyze.txt) |
| Flutter 위젯 | [37개 통과](test-results/automatic-storage/flutter-test.txt). 최초 저장·`+` 자동 생성, 설정 위치 변경, 실패 시 선택 보존, 없는 파일의 길게 누르기 삭제, 기존 화면/선택 회귀 포함 |
| JVM | [24개 통과](test-results/automatic-storage/jvm-summary.json). 새 document ID·상위 경로·심볼릭 링크 탈출 차단 포함 |
| Android 자동 저장소 | [10개 통과](test-results/automatic-storage/storage-device.txt). 비어 있는 루트 설정에서 자동 생성, 반복 초기화·기존 선택 보존, 실제 하위 폴더 생성/이동/이름 변경/삭제와 파일 보존, 영상·오디오 백그라운드 저장, 실제 X 영상 저장, MediaStore 원본 읽기·시스템 파일 열기/공유/위치 열기, 외부 삭제·삭제된 상위 폴더·삭제 저널 복구·접근 불가 제공자·fixture 정리 |
| 기존 폴더 회귀 | [4개 통과](test-results/automatic-storage/hierarchy-regression.txt). 기존 ID/URI 유지·계층·플랫폼 매핑·복구 |
| 기존 작업 제어 | [2개 통과](test-results/automatic-storage/queue-regression.txt). 제어·동시 영상/오디오 다운로드·Activity 종료 후 완료 |
| 마지막 상태·진단 | [2개 통과](test-results/automatic-storage/final-diagnostic.txt). 완료 이벤트 전달, 대기열·Wi-Fi/모바일 설정이 [실행 전](test-results/automatic-storage/before-diagnostic.txt)과 동일 |
| 빌드·설치 | [release 두 ABI](test-results/automatic-storage/release-build.txt), [네이티브 테스트](test-results/automatic-storage/native-build.txt), [실기기 설치](test-results/automatic-storage/install.txt) 성공 |
| APK 내용 | [버전·SHA-256·권한·자산 확인](test-results/automatic-storage/apk-verification.json). 1.0.7, minSdk 24/targetSdk 36, 추가 저장소/갤러리 전체 접근 권한 없음, yt-dlp·사진/스토리 확장 자산 바이트 일치 |
| 네이티브 정렬 | [ARM64 259개·x86_64 261개 ELF 검사](test-results/automatic-storage/native-alignment.json). 16KB 미만 없음 |

Android 최종 합계는 **18개 통과, 실패/건너뜀 없음**입니다. [요약](test-results/automatic-storage/device-summary.json)의 10+4+2+2를 집계하며, 선행 [중단된 fixture 정리](test-results/automatic-storage/fixture-cleanup.txt)는 같은 테스트를 중복 집계하지 않습니다. 실제 X 영상은 629,101 B MP4, 분석 1,116ms·다운로드/검증/자동 저장 총 6,860ms였습니다. 자동 폴더의 실제 JPEG는 MediaStore URI로 다시 읽었고 시스템 앱에서 열기·공유·폴더 위치 열기를 확인했습니다.

중간 실패는 최종 통과와 구분합니다. [위젯의 갱신 대기 부족](test-results/automatic-storage/intermediate/flutter-widget-pump.txt)을 수정했고, [제공자 부재를 삭제로 오인하던 회귀](test-results/automatic-storage/intermediate/unavailable-provider-before.txt)를 재현했습니다. 이후 [사라진 문서의 null 커서 2건](test-results/automatic-storage/intermediate/null-cursor-device.txt)을 실기기에서 확인해 상위 폴더 확인 경로로 통일했습니다. 중단된 테스트 자료만 정리하고 전체 테스트를 재실행했습니다. 사용자 쿠키·기존 파일·저장소 승인은 초기화하지 않았습니다.

검증 기기는 [Silver Max Pro 2 / Android 12 / API 31](test-results/automatic-storage/device.json)입니다. 호스트 보호를 위해 에뮬레이터를 실행하지 않고 `build-safe`의 RAM 6GiB·swap 512MiB·CPU 600% 제한으로 빌드를 직렬 실행했습니다. S22+ Android 16과 x86_64의 실행, 외부 제공자/SD 카드의 모든 권한 철회·동시 수정 조합은 이번에 확인하지 않았습니다. 최초 사용 조건은 루트 설정을 비운 상태에서 검증했고 기존 설치의 데이터를 삭제하지 않았습니다.

산출물: [ARM64 APK](../artifacts/Fetchy-arm64.apk), [x86_64 APK](../artifacts/Fetchy-x86_64.apk), [SHA-256](../artifacts/SHA256SUMS.txt). 기본 폴더의 앱 제거 정책과 위치 변경 동작은 [저장소 문서](storage-cookies.md)를 참조하세요.



## 1.0.8 선택 기능·자동 화질 (2026-10-10)

로그인 유효 여부, 실패 원인/진단 공유, 선택 자막 SRT/VTT, 로컬 재생, 오디오 태그/커버, 기존 파일의 저장 위치 이전을 구현했습니다. 추가 화질 검색 UI를 제거하고 설정 화질 또는 최고 화질을 즉시 선택합니다. [기능·경로·채널·주의점](download-extras.md), [구현 계획과 발견 원인](feature-upgrade-plan.md)을 함께 갱신했습니다. Morit 코드는 수정하지 않았습니다.

| 검사 | 최종 결과 |
| --- | --- |
| Flutter | [analyze 오류 없음](test-results/feature-upgrade/flutter-analyze.txt), [46개 통과](test-results/feature-upgrade/flutter-test.txt). 자동 선택, 자막/오디오 전용 요청, 로그인·진단·이전 명령, 재생 메뉴, 세로 영상 상세, 이전 누락 요약 포함 |
| JVM | [27개 통과, 실패·건너뜀 없음](test-results/feature-upgrade/jvm-summary.json). 오류 분류/비밀 제외, 자동 형식 정렬, Instagram 숫자 PK→shortcode, 기존 URL·네트워크·파일명·엔진 회귀 |
| 최종 ARM64 release 기능 | [10개 통과](test-results/feature-upgrade/physical-features-release.txt). 인증 응답 8개 모의 자료와 분리된 만료 쿠키, 실제 진단 공유 화면·FileProvider 읽기, 3개 오디오 형식의 UTF-8 태그/커버·디코딩, 태그 끄고 커버만 저장, 공개 MDN 자막 요청·SRT 변환·SAF 재읽기, 실제 영상 첫 프레임·오디오/영상 터치 탐색·수명주기, 폴더 이전 충돌/중단/누락/즐겨찾기/URI 복구, 부분 원본 정리 재개, 미완료 자막 게시 복구, 실제 릴스 자동 화질 저장 |
| 기존 기능 회귀 | [21개 통과](test-results/feature-upgrade/physical-regression-release.txt). 자동 루트/실제 폴더 계층/실물 삭제, 화면 종료 후 다운로드·저장, 대기열 제어/복구, 폴더 CRUD/이동 저널, 미리보기 원본 판별. 테스트 네트워크 미디어는 공개 MDN MP4 |
| 빌드·설치 | [두 ABI release 빌드](test-results/feature-upgrade/release-build.txt), [테스트 APK/JVM 빌드](test-results/feature-upgrade/native-release-tests-build.txt), [실제 설치](test-results/feature-upgrade/install.txt). ARM64 code 2009, x86_64 code 4009, minSdk 24/targetSdk 36. [서명·버전·ABI·번들 asset 일치](test-results/feature-upgrade/apk-audit.json), [동일 Kotlin 내부 모듈 이름](test-results/feature-upgrade/module-consistency.json), [네이티브 ELF 520개 16KiB 이상 정렬](test-results/feature-upgrade/native-alignment.json) 확인 |
| 서버 의존 | [Dart/Kotlin 25개 소스 검사](test-results/feature-upgrade/network-source-scan.json): Morit 서버/인증 클라이언트·Firebase·Supabase·Cobalt 참조 없음. 기존 X 공개 보조 분석 API는 유지합니다. 패킷 캡처 검사는 아닙니다 |

실기기는 Silver Max Pro 2 / Android 12이며 최종 설치 APK에서 **31개 통과·건너뜀 없음**입니다. 두 ABI를 빌드했지만 x86_64 실행과 Samsung S22+ / Android 16은 이번 검사에 포함하지 않았습니다. 에뮬레이터를 실행하지 않고 빌드·기기 조작을 공유 잠금으로 직렬화했습니다. 사용자 파일/쿠키/권한을 초기화하지 않고 자신이 만든 테스트 자료만 정리했습니다.

### 실제 자동 화질 시간과 수정

공개 릴스 `DaupWjVARQ_`의 최종 네이티브 미리보기/분석 측정은 **0.676초 / 0.581초**입니다. 1080p 설정과 최고 설정의 다운로드·후처리·저장은 각각 **12.189초 / 11.508초**, 실제 1080×1920이며 원본 대체 경고가 없습니다. 미리보기 시트 위젯의 자동 선택 프레임은 약 17~28ms였습니다. 네이티브 호출 측정이며 모든 사이트의 네트워크 지연이나 전체 앱 첫 실행 시간을 보장하는 값은 아닙니다.

Instagram 임베드 숫자 PK를 yt-dlp shortcode로 비교해야 하는 ID 불일치, 비어 있지 않은 폴더의 검증된 복사 후 삭제, 완료/캐시/기록 삭제가 미완료 자막 게시 원본을 지우던 경로, 오디오 태그와 커버 설정의 결합을 수정했습니다. 원본 삭제 중단 복구는 대상 지문·삭제 단계 저널로 검사합니다.

첫 검사 실패 기록은 `test-results/feature-upgrade/intermediate/`와 `physical-features-first.txt`에 보존합니다. 마지막 진단 공유 확장 검사는 UiAutomation 등록/연결 충돌로 실패했으며 [해당 로그](test-results/feature-upgrade/intermediate/chooser-automation-connection.txt)를 통과로 집계하지 않았습니다. 화면을 열기 전에 연결을 확보한 최종 재실행은 위 10개와 21개 모두 통과했습니다.

### 범위와 산출물

로그인 검사는 인증 응답 모의 자료·분리된 만료 쿠키에 대한 검사이며 실제 로그인 계정의 비공개/멤버십/스토리 다운로드 성공 검증은 아닙니다. 자막은 공개 MDN 원본과 추출 모의 자료를 사용했으며 추가 인증 헤더가 필요한 원본은 실패할 수 있습니다. 앱 내 자막 표시·영상 삽입·백그라운드 음악 재생은 포함하지 않습니다. 이전은 현재 루트의 등록된 최상위 폴더와 그 내부 실제 내용/루트 직속 등록 파일에 적용하며 다른 과거 루트·등록되지 않은 루트 직속 자료는 남습니다. 기본 Android/media 저장 위치의 앱 제거 시 삭제 정책은 유지합니다.

[ARM64 APK](https://github.com/louis1618/fetchy/releases/latest/download/Fetchy-arm64.apk) · [x86_64 APK](https://github.com/louis1618/fetchy/releases/latest/download/Fetchy-x86_64.apk) · [SHA-256](https://github.com/louis1618/fetchy/releases/latest/download/SHA256SUMS.txt) · [검증 요약](test-results/feature-upgrade/summary.json). 재현: `scripts/verify_feature_upgrade.py`와 [빌드 명령](build.md).
