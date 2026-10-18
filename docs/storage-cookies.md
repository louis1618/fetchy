# 저장소·쿠키 동작

## 자동 저장소와 SAF 실제 폴더·파일

1.0.7부터 [Storage.ensureRoot](../android/app/src/main/kotlin/com/luverse/fetchy/Storage.kt)는 저장 위치가 비어 있을 때만 `Android/media/com.luverse.fetchy/Fetchy`를 자동 생성합니다. [FetchyDocumentsProvider](../android/app/src/main/kotlin/com/luverse/fetchy/FetchyDocumentsProvider.kt)는 그 폴더만 `content://com.luverse.fetchy.documents/tree/fetchy`로 제공합니다. 상대 document ID, 정규 경로·이름 검사로 외부 경로와 심볼릭 링크 탈출을 차단하며 루트 삭제와 비어 있지 않은 폴더의 재귀 삭제를 거부합니다. 기존 SAF 게시·검증·저널·하위 폴더 처리 경로를 그대로 사용합니다. 추가 저장소 권한이나 초기 폴더 선택은 필요하지 않습니다.

기본 폴더의 미디어는 MediaStore로 스캔합니다. 이 위치는 Android 앱 전용 미디어 경로이므로 **앱 제거 시 파일도 삭제됩니다**. 설정의 저장 위치에 이 동작을 표시합니다. 장기 보관 위치는 [MainActivity](../android/app/src/main/kotlin/com/luverse/fetchy/MainActivity.kt)의 `ACTION_OPEN_DOCUMENT_TREE`로 변경하고 영속 읽기·쓰기 권한을 확보합니다. 기존 권한은 유지합니다. 1.0.8은 새 다운로드만 적용하거나 현재 저장 위치의 기존 자료도 이전하는 선택을 제공합니다. 저장소가 없는 상황은 오류를 표시하며 다른 위치로 몰래 저장하지 않습니다. [Android API의 경로·권한·삭제 정책](https://developer.android.com/reference/android/content/Context#getExternalMediaDirs())을 따릅니다.

- 폴더 생성·열기·이름 변경·삭제는 실제 폴더에 적용됩니다. 사용 중인 폴더의 변경은 제한됩니다.
- 폴더 삭제는 DB에 없는 파일·중첩 폴더도 열거해 **실제 상위 폴더로 이동한 뒤** 빈 폴더를 삭제합니다. 일시정지·실패·취소 작업의 재개/재시도 대상도 같은 상위 위치로 바꿉니다.
- 플랫폼 폴더를 삭제하면 해당 루트의 자동 매핑을 비활성화하며 루트 재선택 후에도 유지합니다.
- 위치만 변경하면 기존 파일·레코드는 원래 `rootUri`에 남습니다. 기존 파일 이전을 선택하면 현재 루트의 기록된 최상위 폴더와 그 안의 실제 내용, 루트 직속 파일을 이동합니다. 앱에 등록되지 않은 루트 직속 자료는 이동하지 않습니다. 다른 과거 루트의 자료와 찾을 수 없는 기록은 기존 위치에 남기며 이전 권한을 해제하지 않습니다.
- 파일 열기·공유는 URI 읽기 권한을 전달합니다. 이름 변경·이동·삭제도 실제 문서 작업입니다. 다운로드 기록 삭제는 저장된 파일 삭제와 별개입니다.
- 파일 URI가 유효하면 기존처럼 실제 파일을 삭제합니다. 사라진 tree URI 조회가 `Failed to determine if primary…` 같은 예외로 실패하면 `presentInParent`가 접근 가능한 루트부터 상위 폴더를 열거해 부재를 확인하고 파일 레코드·삭제 저널을 정리합니다. 파일·상위 폴더가 사라진 경우와 삭제 저널의 재시작을 같은 경로로 처리합니다. 권한/제공자 장애는 기록을 유지하고 오류를 반환합니다.
- 게시 중복 정책은 새 이름·기존 파일 사용(`skip`)·교체입니다. 기본은 새 이름입니다.

Room `folderOps` 저널은 게시·이동·이름 변경·파일/폴더 삭제의 진행을 보존합니다. 복사 방식의 게시·이동은 SHA-256 검증 후 확정·원본 삭제를 수행하며 제공자가 지원하면 네이티브 이동을 사용합니다. 재시작 시 저널을 재처리합니다. 접근 불가 자료는 `missing`으로 표시하고 레코드·실패 저널을 유지합니다. 시스템 폴더 승인·실제 CRUD·삭제 시 파일 보존·게시/열기/공유는 기기에서 검증했습니다. 권한 회수, 제공자 변경, 동시 외부 수정의 전체 조합은 별도 검증 대상입니다.

## 쿠키

[CookieVault](../android/app/src/main/kotlin/com/luverse/fetchy/Cookies.kt)는 21개 쿠키 저장소의 허용 도메인을 검사합니다. YouTube·Instagram·TikTok·X 외에 Facebook·Threads·Reddit·Pinterest·Twitch·Vimeo·SoundCloud·Bilibili·Douyin·Weibo·Naver·SOOP·Kakao·VK·OK.ru·Bandcamp·Mixcloud를 연결합니다. Naver는 네이버TV·치지직·카페·블로그, Kakao는 KakaoTV·KakaoStory에 같은 계정 저장소를 사용합니다. UTF-8 Netscape 7필드 형식의 파일을 최대 2 MiB까지 가져오며 원래 도메인·경로·만료·HttpOnly 정보를 보존합니다.

저장은 Android Keystore의 AES-256-GCM 키와 AtomicFile을 사용합니다. 암호문은 `noBackupFilesDir/fetchy-cookies`, 실행용 평문은 `fetchy-cookie-temp`에 제한된 권한으로 생성합니다. Engine은 실행 종료 시 평문을 삭제하고 Runtime은 시작 시 잔여 평문을 정리합니다. 앱 백업은 비활성화되어 있습니다.

WebView 로그인은 허용 HTTPS 도메인으로 제한됩니다. 제공자가 WebView 인증을 차단하면 Netscape 파일 가져오기를 사용합니다. CookieManager는 원래 범위·만료·HttpOnly 메타데이터를 열거하지 못하므로 WebView 내보내기는 호스트 전용, `/` 경로, Secure 세션 쿠키로 저장하고 보수적으로 HttpOnly를 표시합니다. 원래 메타데이터가 필요한 경우 파일 가져오기를 사용하세요.

플랫폼 쿠키 삭제는 해당 암호화 저장소·평문을 삭제하고 **모든 WebView 로그인 세션**도 비동기로 지웁니다. 다른 플랫폼의 암호화 저장소는 유지됩니다. 쿠키는 필요한 콘텐츠 요청에 사용되며 Fetchy 백엔드는 없습니다.

1.0.5부터 스토리·하이라이트 URL도 기존 Instagram 쿠키를 사용하는 로컬 추출기로 연결합니다. 모바일 호스트는 `www.instagram.com`으로 정규화하고 공유 쿼리는 유지합니다. 업데이트는 저장된 암호화 쿠키를 교체하지 않습니다. 로그인된 계정의 원본 접근 권한·스토리 만료 여부는 플랫폼에서 판단하며, URL 분류 성공만으로 콘텐츠 접근 성공을 표시하지 않습니다.

## Nested folders

`folderCreate` accepts a parent ID and color. `folderMove` rejects cycles and collisions. Rename/move rebind all descendant document URIs through durable journals. Delete preserves contents in the deleted folder's actual parent and reparents nested records. Active jobs anywhere in the affected subtree block the operation; paused/failed jobs retain valid destinations. Platform mappings survive rename/move by ID. Favorites and media metadata survive file moves.

1.0.6의 `CookieVault.platformForUrl`은 요청 원본 사이트로 저장소를 선택합니다. CDN 주소에는 플랫폼 쿠키를 연결하지 않으며 각 평문 파일에는 해당 저장소의 허용 도메인만 들어갑니다. `snapshot.cookiePlatforms`가 설정 목록의 기준이므로 네이티브에서 추가한 사이트를 Flutter에서도 가져오기·로그인·삭제할 수 있습니다. 기존 네 개 저장소의 ID·암호화 키·파일 형식은 유지합니다. 새 플랫폼의 실제 로그인·추가 인증 성공을 모두 검증한 것은 아닙니다.

## 1.0.8 위치 이전·로그인 확인

`Storage.prepareMigration/migrate`는 `folderOps/storage-migration`에 항목별 진행을 저장합니다. 진행 중 다운로드, 원본 내부 목적지, 다른 미완료 파일 작업을 제한합니다. 충돌은 새 이름으로 보존하고 복사는 SHA-256으로 검증한 뒤 원본을 삭제합니다. 하위 폴더·파일 URI, 즐겨찾기, 플랫폼 매핑 ID와 일시정지 작업의 목적지를 갱신합니다. 사라진 항목은 기존 기록에 남기고 완료 요약에 수를 표시합니다. 실패 시 설정에서 **이전 계속하기**를 실행합니다. OS 중지는 대기 상태로 복원하며 강제 중지 후에는 앱을 다시 열어주세요.

`LoginChecks`는 만료 쿠키를 제외한 첫 당사자 요청만 보내며 인증 근거가 없는 공개 HTTP 200은 `unknown`입니다. 확인 상태는 5분간 캐시하고 쿠키 교체·삭제 시 무효화합니다. 성공/로그아웃/만료/미확인/확인 불가를 구분하고 계정 식별자·응답 본문을 저장하지 않습니다. 확인은 사용자 메뉴에서 실행하며 다운로드의 필수 단계가 아닙니다. 실제 인증된 계정의 권한·추가 인증·구독 자격은 별개입니다.

선택 자막 게시 저널에 언어·미디어 연결 ID도 저장합니다. 저장 실패 후 캐시 정리를 눌러도 게시 복구에 필요한 내부 원본은 보존합니다.

## 1.0.9 X 인증과 파일 날짜

WebView의 기존 host-only `x.com` 쿠키는 yt-dlp가 로그인 여부와 CSRF를 확인하는 `api.x.com`에서 보이지 않았습니다. `CookieVault.temporary`는 `auth_token/ct0`에만 첫 당사자 API용 host-only 행을 추가합니다. 원래 행·가져온 도메인 범위·암호화 파일은 유지하며 임시 파일은 실행 후 삭제합니다. 새 WebView 저장도 `api.x.com`을 조회합니다. 기존의 빠른 공개 조회를 유지하며, 공개 조회가 불가능하고 로그인 쿠키가 있으면 익명 syndication 단계를 건너뛰어 해당 세션으로 원본을 추출합니다. 익명 분석의 빈 형식 목록은 다음 추출 경로로 진행합니다. 쿠키는 보조 분석 서비스나 미디어 CDN 도메인에 복제하지 않습니다.

설정 `fileDate`는 `download`(기본 현재 저장 시각) 또는 `source`(플랫폼 게시 시각)입니다. `MediaDates.kt`는 실제 timestamp 초를 밀리초로 변환하고, upload_date만 있으면 UTC 날짜로 검증합니다. yt-dlp 원본, Instagram 임베드, X 공개 응답의 날짜를 `asset.sourceTimestamp`에 보존합니다. 게시 시각이 없으면 현재 시각을 쓰며 안내를 기록합니다.

작업의 `mediaDate`는 날짜 기반 파일명과 게시 저널에 보관합니다. 파일 레코드에도 `mediaDate/sourceTimestamp/dateApplied`를 저장합니다. 갤러리 날짜 갱신 권한은 스캔 후 `galleryDateApplied`에 별도로 기록합니다. 승인된 Content URI의 파일 디스크립터로 실제 수정 시각을 적용하고, 미디어 스캔 후 권한이 있는 이미지·영상 MediaStore의 DATE_TAKEN에도 반영을 시도합니다. SAF로 선택한 폴더는 파일 쓰기 권한과 MediaStore 행 수정 권한이 달라 갤러리 메타데이터 갱신이 거부될 수 있습니다. 다운로드 이력의 createdAt/completedAt은 유지하며, 상세 화면은 선택된 저장 날짜를 표시합니다. EXIF나 영상 내부의 촬영 메타데이터를 다시 인코딩하지는 않습니다.

중복 건너뛰기는 기존 파일 시각을 바꾸지 않습니다. 게시 복구·파일/폴더 이동은 날짜를 보존합니다. 날짜 변경을 지원하지 않는 저장 제공자는 파일 저장을 완료하고 dateApplied=false와 안내를 남깁니다. 갤러리 앱의 정렬 기준에 따라 표시 순서는 다를 수 있습니다.
