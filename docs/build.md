# 로컬 Android 빌드

프로젝트 루트는 `/home/louis/Documents/.dev/Fetchy`입니다. Flutter(Dart SDK `^3.13.2` 충족), JDK 17, Android SDK·NDK `28.2.13676358`, CMake, Python 3.12 이상이 필요합니다. Python 3.12는 앱에 포함되는 런타임 버전이기도 합니다. 엔진 재현과 의존성 확보에는 인터넷이 필요합니다.

```bash
cd /home/louis/Documents/.dev/Fetchy
export FETCHY_NDK="$HOME/Android/Sdk/ndk/28.2.13676358"
python3 scripts/build_engine.py
flutter pub get
flutter build apk --debug --target-platform android-arm64,android-x64
flutter build apk --release --split-per-abi --target-platform android-arm64,android-x64
```

결과 APK: `build/app/outputs/flutter-apk/app-debug.apk`, `app-arm64-v8a-release.apk`, `app-x86_64-release.apk`. 연결된 지원 기기에서 개발 실행은 `flutter run`입니다. 엔진 파일이 이미 준비되었다면 엔진 재현 단계는 생략할 수 있습니다.

[Gradle 설정](../android/app/build.gradle.kts)은 minSdk 24와 두 ABI를 지정합니다. 현재 release도 debug 키로 서명하는 로컬 설치용 설정입니다. 외부 배포에는 Fetchy 소유 서명 키와 [소스·고지 묶음](source-reproduction.md)을 준비해야 합니다.

## 테스트 명령

```bash
cd /home/louis/Documents/.dev/Fetchy
flutter analyze
flutter test
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

마지막 명령은 지원 ABI의 연결 기기 또는 에뮬레이터가 필요합니다. 네이티브 엔진 테스트는 외부 미디어 사이트에 접속합니다. 폴더 테스트 전에 앱에서 테스트용 SAF 루트를 선택하세요. 루트가 없으면 폴더 CRUD 사례는 건너뛰며, 직접 미디어 테스트의 SAF 게시 검증도 수행되지 않습니다. 쿠키 테스트는 테스트용 Instagram 저장소를 덮어쓰고 삭제하므로 별도 테스트 설치를 사용하세요.

실기기 전체 점검은 테스트 APK 빌드 후 아래처럼 실행합니다. 기존 쿠키를 교체하는 테스트가 있으므로 테스트용 설치에서만 실행하세요. 워크스테이션의 `build-safe`는 빌드를 직렬화하며 busy일 때 exit 75를 반환하므로 다른 빌드가 끝난 뒤 재실행합니다.

```bash
cd /home/louis/Documents/.dev/Fetchy
python3 scripts/test_device.py --serial DEVICE_SERIAL --apk build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
```

결과는 [검증 현황](verification.md)과 `docs/test-results/`에 기록합니다.

## 현재 워크스테이션의 에뮬레이터

[전용 실행 스크립트와 자원 제한](emulator-workstation.md)을 사용합니다. 에뮬레이터가 공유 빌드 잠금을 보유하므로 테스트 APK 빌드 후 실행하세요. 시작이 exit 75이면 자원·잠금 조건이 충족될 때까지 기다립니다.

반복적인 네이티브 대기열 테스트에서는 Android 16 에뮬레이터의 GLES 렌더러 충돌을 피하기 위해 다음 인자를 사용할 수 있습니다. 테스트 helper가 Flutter Activity에 `enable-impeller=false`와 `enable-software-rendering=true`를 전달합니다. APK의 기본 렌더링 설정은 별도입니다.

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.luverse.fetchy.DispatchDeviceTest#repeatedSchedulingDoesNotRestartTheRunner \
  -e softwareRendering true \
  com.luverse.fetchy.test/androidx.test.runner.AndroidJUnitRunner
```

## 1.0.8 보조 기능 회귀

네이티브 Kotlin 모듈 이름은 두 빌드 변형 모두 `fetchy`로 고정합니다. 테스트 APK가 설치된 release의 내부 보조 메서드를 동일한 이름으로 호출하도록 하기 위한 설정입니다. 동일 소스로 release 두 ABI와 Android 테스트 APK를 빌드한 뒤 실행합니다. 기존 쿠키·파일·루트 권한을 초기화하지 않으며 테스트 자료만 만들고 정리합니다. 진행 중 다운로드가 있으면 이전 관련 검사는 건너뛰고 검증 스크립트는 성공으로 처리하지 않습니다.

```sh
build-safe python3 scripts/verify_feature_upgrade.py --serial DEVICE_SERIAL --apk build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
```

공유 테스트폰을 사용하는 다른 프로젝트의 Flutter drive와 충돌하지 않도록 기기 검증에도 같은 빌드 잠금을 사용합니다. 로그는 `docs/test-results/feature-upgrade`에 저장합니다. 실제 인증된 계정 없이 확인한 로그인 응답 검사는 실계정 성공 검증과 구분합니다.
