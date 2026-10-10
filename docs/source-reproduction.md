# 소스 재현·배포 자료

사용자가 GPL 소스·라이선스 고지 포함 방침을 승인했습니다. 프로젝트의 [GPLv3 전문](../LICENSE)과 [서드파티 고지](../THIRD_PARTY_NOTICES.md)를 APK와 대응 소스 배포에 포함합니다.

[build_engine.py](../scripts/build_engine.py)는 다음 고정 입력을 SHA-256으로 확인합니다.

| 입력 | 버전 | SHA-256 |
| --- | --- | --- |
| yt-dlp 릴리스의 `yt-dlp` 파일 | 2026.08.19 | `1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6` |
| QuickJS-NG 태그 소스 tarball | 0.17.0 | `559bc4c420475e55c7ab4510adbc562f55d7524d75e8e89d79ce4bb02f5687d9` |

입력 URL은 스크립트에 있습니다. yt-dlp는 `android/app/src/main/assets/yt-dlp`에 저장합니다. QuickJS는 `.cache/engine`에서 NDK·CMake로 API 24용 ARM64/x86_64 실행 파일을 빌드하고 `android/app/src/main/jniLibs/<ABI>/libfetchyqjs.so`에 배치합니다. 링커는 16 KiB 최대 페이지 크기를 지정합니다. 확장자가 `.so`인 QuickJS 파일은 실행 파일입니다.

[Gradle](../android/app/build.gradle.kts)은 wrapper library와 FFmpeg `0.18.1`을 Maven에서 가져옵니다. Python `3.12`는 wrapper의 번들 런타임이며 이 스크립트가 Python·FFmpeg·wrapper를 빌드하지는 않습니다. Engine은 번들 yt-dlp 해시를 확인해 앱 전용 위치에 설치하며 `--no-update`를 사용합니다.

APK의 yt-dlp asset은 위 SHA의 원본 zipapp을 유지합니다. 1.0.4의 [BundledPython.kt](../android/app/src/main/kotlin/com/luverse/fetchy/BundledPython.kt)는 설치용 ZIP에서만 23바이트 실행문을 제거해 최신 Android ZIP 헤더 검사와 호환시킵니다. 원본 코드·ZIP 엔트리·엔진 버전을 변경하지 않습니다. `modules-v3` 설치 마커가 이전 설치를 복구합니다.

1.0.5의 [Fetchy 스토리 추출 확장](../android/app/src/main/assets/fetchy_instagram_story.py)은 별도 앱 소스로 APK assets에 포함합니다. 기존 `InstagramStoryIE`를 상속해 사진 스토리 원본을 보존하며 번들 zipapp은 수정하지 않습니다. 대응 소스 배포에는 이 파일과 [번들 검사 스크립트](../scripts/check_instagram_stories.py)를 함께 포함합니다.

재현 명령은 [로컬 빌드](build.md)에 있습니다. 이 절차는 고정 엔진 입력과 빌드 경로를 설명하며 바이트 단위 동일 APK 재현을 검증한 결과는 아닙니다.

배포 시 APK에 대응하는 Fetchy 소스(`lib/`, `android/`, `scripts/`, pubspec·lockfile·Gradle 설정), 변경 사항, 빌드 명령·도구 버전, 라이선스·저작권 고지를 함께 제공하세요. 포함된 wrapper·FFmpeg·Python 및 전이 구성요소의 해당 버전 소스와 필요한 빌드 자료·고지도 실제 배포 묶음에 연결해야 합니다. 현재 저장소의 라이선스 파일만으로 모든 번들 구성요소의 고지가 완료되었다고 단정하지 않습니다. 배포용 소스 묶음·공개 위치의 확인 결과는 통합 담당자가 기록합니다.

## FFmpeg WebP 보조 라이브러리 교체

[build_webp.py](../scripts/build_webp.py)는 libwebp v1.6.0 공식 소스(SHA-256 `93a852c2b3efafee3723efd4636de855b46f9fe1efddd607e1f42f60fc8f2136`)와 FFmpeg wrapper 0.18.1 AAR(SHA-256 `0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49`)를 고정합니다.

ARM64/x86_64에서 5개 WebP 라이브러리를 16KB 정렬·원래의 버전 없는 SONAME으로 빌드하고 FFmpeg zip의 해당 파일만 교체합니다. 프로젝트의 libffmpeg.zip.so가 Maven 원본보다 우선하도록 Gradle pickFirsts를 설정합니다. [check_native_alignment.py](../scripts/check_native_alignment.py)는 완성 APK와 내부 Python/FFmpeg zip의 모든 ELF를 검사하며, 실제 APK의 zip 해시가 프로젝트의 교체 zip과 같은지도 검증해야 합니다. build_engine.py가 이 단계를 함께 실행합니다.

1.0.6은 [개별 게시물 사진 확장](../android/app/src/main/assets/fetchy_photos.py)과 [원본 응답 검사](../scripts/check_post_photos.py)를 추가합니다. Instagram 확장도 게시물 사진 처리까지 포함합니다. 두 소스 asset은 Engine이 전용 확장 디렉터리에 원자적으로 설치하며 원본 yt-dlp zipapp과 엔진 버전은 바꾸지 않습니다. 대응 소스 묶음에 두 확장과 두 검사 스크립트를 모두 포함하세요.
