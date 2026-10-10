# Fetchy

Fetchy는 링크를 기기에서 분석해 영상·오디오·이미지와 자막을 폴더별로 저장하는 Android 앱입니다. Flutter UI와 Android 다운로드 엔진을 사용하며 Morit 서버가 필요하지 않습니다.

## Platforms

YouTube, Instagram, TikTok, X, Reddit, Pinterest, Facebook, Bluesky, Twitch, Vimeo, Naver TV, CHZZK, Bilibili, Bandcamp, Streamable 등 **43개 플랫폼 URL 유형**을 인식합니다. URL 인식이 모든 게시물의 다운로드 성공을 보장하지는 않습니다. 실제 지원 사례와 제한은 [플랫폼 지원표](docs/platform-support.md)를 확인하세요.

## Features

- 링크 붙여넣기·공유, 미리보기, 미디어·화질·포맷 선택. 영상/오디오 탭은 선택한 유형만 저장하며 화질은 설정값 또는 최고 화질로 자동 선택합니다.
- 진행률을 확인하며 개별 다운로드를 대기열에 넣고 일시정지·재개·취소·재시도하거나 백그라운드에서 실행합니다.
- 미디어와 선택 자막(SRT/VTT)을 저장하고, 오디오 태그·커버를 추가하며 영상·오디오를 앱에서 재생합니다.
- 폴더와 하위 폴더, 즐겨찾기를 관리하고 Android 저장소 앱에서 파일을 열거나 공유합니다.
- 플랫폼 쿠키 가져오기·로그인 확인, 저장소·네트워크 설정, 개인정보를 거른 오류 진단 공유, 중단 후 이어지는 저장 위치 이전을 제공합니다.

## Architecture

Flutter가 화면을, Kotlin이 영속 대기열·Android 저장소·백그라운드 처리를 담당합니다. 번들 yt-dlp·Python·QuickJS-NG·FFmpeg로 미디어를 기기에서 분석·처리합니다. X는 공개 게시물의 미디어 주소 조회에 보조 resolver를 사용하며 사용자 쿠키를 전달하지 않습니다.

Android 7.0(API 24) 이상이 필요하며 ARM64와 x86_64 APK를 제공합니다. 자세한 설계는 [아키텍처](docs/architecture.md), [저장소·쿠키](docs/storage-cookies.md), [기능·제한](docs/download-extras.md)을 참고하세요.

## Install

기기에 맞는 APK를 [최신 GitHub 릴리스](https://github.com/louis1618/fetchy/releases/latest)에서 받으세요.

- [ARM64 APK](https://github.com/louis1618/fetchy/releases/latest/download/Fetchy-arm64.apk)
- [x86_64 APK](https://github.com/louis1618/fetchy/releases/latest/download/Fetchy-x86_64.apk)
- [SHA-256 checksums](https://github.com/louis1618/fetchy/releases/latest/download/SHA256SUMS.txt)

현재 APK는 로컬 개발 서명 키를 사용합니다. 기존 앱과 서명 키가 다르면 업데이트 전에 기존 앱을 삭제해야 할 수 있습니다.

## Build and verify

빌드 방법은 [빌드 문서](docs/build.md)를 참고하세요. 1.0.8은 Flutter 분석과 위젯 46개, JVM 27개, Android 12 실기기 31개 검사를 통과했습니다. 상세 결과와 제한은 [검증 기록](docs/verification.md)에 있습니다.

## License

Fetchy는 GPL-3.0으로 배포합니다. 번들 구성요소와 고지는 [LICENSE](LICENSE)와 [서드파티 안내](THIRD_PARTY_NOTICES.md)를 확인하세요.
