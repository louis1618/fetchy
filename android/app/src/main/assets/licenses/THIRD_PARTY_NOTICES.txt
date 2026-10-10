# 서드파티 고지

Fetchy는 GPL 소스·라이선스 고지 포함 방침으로 제공됩니다. 프로젝트 라이선스 전문은 [LICENSE](LICENSE)에 있습니다. 아래는 실제 코드·빌드 설정과 로컬 라이선스 파일에 따른 핵심 구성요소 목록입니다.

| 구성요소 | 고정 버전/용도 | 로컬 고지·소스 위치 |
| --- | --- | --- |
| yt-dlp | 2026.08.19, 미디어 추출·다운로드 | [Unlicense 전문](licenses/yt-dlp.txt), [번들 입력](scripts/build_engine.py), [업스트림](https://github.com/yt-dlp/yt-dlp) |
| QuickJS-NG | 0.17.0, JavaScript 실행 | [MIT 전문·저작권](licenses/QuickJS-NG.txt), [재현 스크립트](scripts/build_engine.py), [업스트림](https://github.com/quickjs-ng/quickjs) |
| youtubedl-android | library/ffmpeg 0.18.1, Android wrapper | [GPLv3 전문](licenses/youtubedl-android.txt), [의존성 설정](android/app/build.gradle.kts), [업스트림](https://github.com/yausername/youtubedl-android) |

Python 3.12와 FFmpeg/ffprobe는 wrapper가 공급하는 번들 구성요소입니다. 각 구성요소·전이 의존성의 라이선스와 대응 소스는 실제 사용 아티팩트 기준으로 배포 자료에 포함해야 합니다. Flutter/Dart, AndroidX Room·Core·DocumentFile, Cupertino Icons 등도 [pubspec.yaml](pubspec.yaml)·[pubspec.lock](pubspec.lock)·[Gradle](android/app/build.gradle.kts)에 기록됩니다. 위 표는 전체 전이 의존성 목록을 대체하지 않습니다.

Morit 재사용 범위는 플랫폼·선택·저장소 시그니처입니다. Fetchy 자체 소스와 필요한 원본 고지는 함께 보존합니다. 원본 프로젝트: `/home/louis/Documents/.dev/Moring_Morit`.

[소스 재현·배포 자료](docs/source-reproduction.md)에 고정 입력 해시, 출력 경로, 대응 소스 제공 범위를 기록했습니다. 라이선스 전문은 기존 `LICENSE`와 `licenses/` 파일을 그대로 참조합니다.

## libwebp 1.6.0 (16KB 페이지 호환 재빌드)

FFmpeg 번들의 libwebp, decoder, demux, mux, sharpyuv를 공식 v1.6.0 소스로 재빌드합니다. [공식 소스](https://github.com/webmproject/libwebp/tree/v1.6.0), [라이선스](licenses/libwebp.txt), [빌드 스크립트](scripts/build_webp.py)를 참조하세요. 기존 SONAME을 유지하고 16KB ELF LOAD 정렬을 적용했습니다. FFmpeg 자체 코드는 변경하지 않습니다.
