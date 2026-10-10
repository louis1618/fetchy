# 개별 미디어 링크 지원 · Fetchy 1.0.8

전체 계정·프로필 수집, 여러 URL 일괄 입력, 구독 자동 다운로드는 이번 확장에 포함하지 않습니다. 한 게시물 안의 사진·영상·오디오 자산 선택과 기존 다운로드 대기열은 유지합니다.

**43개는 URL 분류·엔진 연결 등록 수이며 모든 링크의 다운로드 성공 수가 아닙니다.** 실제 성공, 모의 응답 검사, 미검증 항목은 [검증 현황](verification.md#106-개별-플랫폼미디어-확장-2026-10-08)에 구분합니다. 원본의 보기 권한·만료·DRM·IP/지역 제한은 플랫폼이 판단합니다.

## 새로 등록한 플랫폼 14개

| 플랫폼 | 연결한 개별 링크 |
| --- | --- |
| Naver TV | 영상 `v`, 임베드, 개별 라이브 주소 |
| CHZZK | 개별 다시보기·라이브 주소 |
| SOOP | 개별 VOD·캐치스토리·방송 주소; 과거 AfreecaTV 도메인 정규화 |
| KakaoTV | 개별 `cliplink`·임베드 영상 |
| Bilibili | 개별 영상·에피소드·오디오·동적 게시물, 국제 사이트 개별 영상·라이브 주소 |
| Douyin | 개별 영상·단축 공유 주소 |
| Weibo | 개별 게시물·TV 영상 주소 |
| VK | 개별 영상·클립·wall 게시물·영상 임베드 |
| OK.ru | 개별 영상·영상 임베드·라이브 주소 |
| Rumble | 개별 영상·영상 임베드 |
| Kick | 개별 VOD·클립·방송 주소 |
| Bandcamp | 개별 트랙·라디오 에피소드 |
| Mixcloud | 개별 에피소드 |
| Streamable | 개별 영상·임베드 |

이 사이트들의 기존 yt-dlp 추출기를 사용하며 새 분석 서버를 추가하지 않습니다. 일부 사이트의 브라우저 검사·지역·인증 응답에 따라 실패할 수 있습니다. 라이브 주소 연결은 예약 녹화나 별도의 녹화 종료 UI를 제공한다는 의미가 아닙니다.

## 기존 플랫폼에서 추가한 기능

| 플랫폼 | 추가 처리 |
| --- | --- |
| Instagram | `/share/p/…`, `/share/reel/…` 리다이렉트; 사용자 이름이 포함된 개별 게시물 주소; 인증 API의 사진·혼합 캐러셀 원본 |
| TikTok | `/photo/…` 사진 게시물 원본; 게시물 음악을 별도 오디오 자산으로 선택; 개별 `share/video`·`embed` 주소 |
| Pinterest | 이미지 핀의 `images.orig` 원본; 일부 국가 도메인 |
| Reddit | `gallery` 주소를 해당 게시물로 연결; `gallery_data` 사진·애니메이션 및 직접 이미지 게시물 원본 |
| Bluesky | 게시물 레코드의 원본 이미지 blob; 여러 이미지 선택 |
| Facebook | `video.php`, `video/video.php`, `photo.php`, `story.php`, `permalink.php`, 영상 임베드 주소의 내장 추출기 연결. **일반 사진·스토리 전부를 저장하는 구현은 아님** |
| X | Spaces·방송·이벤트·영상 카드 주소. 게시물 전용 FxTwitter를 요청하지 않고 내장 추출기 사용 |
| Twitch | 클립 전용 호스트, 영상 플레이어, 기존 VOD 주소 변형 |
| Vimeo | 비공개 공유 해시가 포함된 개별 주소 |
| Dailymotion | 개별 임베드·플레이어 주소 |
| Flickr | 개별 미디어 뒤에 `in/photostream` 등이 붙은 주소. **사진 추출 확장은 아직 없음** |
| SoundCloud | 비공개 트랙의 `s-…` 토큰·API 트랙·플레이어 주소 |
| YouTube | 기존 개별 영상 기능 유지, `/v/…` 주소 추가 |
| 공통 | 영상·오디오가 합쳐진 원본만 있는 경우 M4A·MP3·Opus 오디오 추출 선택 제공 |

사진은 원본을 명시하는 게시물/API 필드에서만 가져옵니다. OG 이미지·영상 썸네일을 원본 다운로드로 사용하지 않습니다. TikTok 사진 게시물의 음악은 사진 탭에서 함께 등록되지 않습니다.

## 쿠키 연동 21개 저장소

기존 YouTube·Instagram·TikTok·X에 Facebook·Threads·Reddit·Pinterest·Twitch·Vimeo·SoundCloud·Bilibili·Douyin·Weibo·Naver·SOOP·Kakao·VK·OK.ru·Bandcamp·Mixcloud를 추가합니다.

Naver 저장소는 Naver TV·CHZZK·Naver Cafe·Naver Blog에, Kakao 저장소는 KakaoTV·KakaoStory에 연결됩니다. 로그인 UI와 Netscape 파일 가져오기는 같은 암호화 저장소를 사용합니다. 새 사이트의 실제 로그인 성공을 모두 검증한 것은 아니며 WebView 차단 시 파일 가져오기를 사용할 수 있습니다. [쿠키 동작](storage-cookies.md#쿠키)

## 기존 등록 플랫폼

YouTube, Instagram, Facebook, Threads, TikTok, X, Bluesky, Reddit, Pinterest, Snapchat, LinkedIn, Tumblr, Twitch, Vimeo, Dailymotion, Discord, Telegram, KakaoStory, Naver Cafe, Naver Blog, Naver Band, LINE, Medium, Substack, WordPress, Behance, Flickr, Imgur, SoundCloud.

기존 등록만 있고 실제 다운로드 성공이 확인되지 않은 사이트를 새 지원 성공으로 집계하지 않습니다. 특히 Threads·Discord 메시지·일부 일반 웹페이지는 내장/일반 추출기가 원본을 찾지 못할 수 있습니다.

## 남은 범위

- Instagram의 로그인된 실제 스토리·하이라이트 저장은 별도 검증 필요.
- Facebook 일반 사진·스토리 전용 추출, Flickr 정적 사진, Imgur 정적 사진 전용 확장은 미구현.
- Snapchat 스토리 주소, Telegram 비공개 메시지, Discord DM은 추가하지 않음.
- 라이브 전용 녹화 제어, 본문·댓글 저장, DRM 처리는 미구현.
- 1.0.8은 추출기가 제공하는 수동/자동 자막 언어를 SRT/VTT로 저장합니다. 자막이 제공되지 않는 자산에는 선택 UI를 표시하지 않습니다. 모든 플랫폼의 자막을 보장하지 않습니다.
- 등록 밖의 웹사이트는 기존 `Web page → yt-dlp` 경로 유지.

근거: [공통 플랫폼 등록](../android/app/src/main/kotlin/com/luverse/fetchy/MediaPlatforms.kt), [엔진](../android/app/src/main/kotlin/com/luverse/fetchy/Engine.kt), [사진 추출](../android/app/src/main/assets/fetchy_photos.py), [쿠키 등록](../android/app/src/main/kotlin/com/luverse/fetchy/Cookies.kt).
