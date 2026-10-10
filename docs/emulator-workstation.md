# 이 작업 PC의 에뮬레이터 실행

확인 사양: Ryzen 5 5600G(6코어/12스레드), RAM 16GB, Radeon 내장 그래픽. 조사 당시 스왑 4GB가 거의 가득 찼다. 현재 부팅의 커널 로그에서는 OOM/GPU hang을 확인하지 못했으므로 마우스 정지의 원인을 단정하지 않는다.

Fetchy의 Android 16 검증은 [emulator_safe.sh](../scripts/emulator_safe.sh)로 실행한다.

```sh
scripts/emulator_safe.sh start
scripts/emulator_safe.sh status
scripts/emulator_safe.sh stop
```

- 한 대만 실행: 기존 에뮬레이터를 감지하면 시작을 거절한다.
- 전용 `Fetchy_API_36` AVD: 가상 CPU 2개, 게스트 RAM 1536MB, 540×960/240dpi, VM heap 128MB, 카메라 비활성화.
- headless, SwiftShader 소프트웨어 그래픽, Vulkan·스냅샷·오디오 비활성화. 호스트 GPU를 사용하지 않는다.
- systemd 사용자 서비스: CPU 상한 150%, 총 메모리 max 4GB, 스왑 사용 금지, nice 10. 게스트 RAM과 총 메모리 제한은 별개다. 총 메모리에는 QEMU·렌더러·커널의 파일 캐시도 포함한다. 상한 초과 시 에뮬레이터가 종료될 수 있다.
- `MemoryHigh`는 적용하지 않는다. 낮은 high 값에서 반복적인 회수·throttling이 발생할 수 있다([Linux 커널 문서](https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html#memory-interface-files)). 대신 max 제한과 압력 감시를 사용한다.
- 시작 전 가용 메모리 6GB 이상과 메모리 PSI `some avg10` 5% 미만을 확인한다. 부족하면 시작하지 않는다.
- 실행 중 5초마다 확인한다. 가용 메모리가 2GB 미만이거나 PSI 10% 이상이 두 번 연속 관찰되면 해당 에뮬레이터만 종료한다. 컴퓨터의 다른 프로세스·스왑은 건드리지 않는다.
- 기존 `$XDG_RUNTIME_DIR/build-safe.lock`을 실행 종료까지 보유한다. `build-safe`를 사용하는 빌드는 에뮬레이터가 종료된 뒤 수행한다. 잠금 충돌은 종료 코드 75이며 보호를 우회하지 않는다.
- 검증이 끝나면 `stop`으로 종료한다. 기존 Mova AVD나 다른 앱·시스템 스왑 설정을 변경하지 않는다.

초기 1536MB/720p 구성에서 메모리 압력이 상승해 종료했다. high를 2816MB로 조정한 실행은 부팅에 성공했으나 APK 설치 중 PSI 19%가 관찰되어 감시기가 자동 종료했다(systemd 메모리 peak 2.8GB). 게스트 RAM을 1024MB로 줄인 실행도 설치 중 자동 종료했다. 최종 구성은 해상도 540p를 유지하고 high 제한을 제거해 회수 압력을 줄이며, 충분한 시작 여유·총 4GB 상한·스왑 금지·자동 종료를 적용한다. 부팅 성공만으로 APK 테스트 성공을 의미하지 않는다. 검증 로그는 [dispatch-fix](test-results/dispatch-fix/)에 보관한다.

최종 제한 아래에서 Android 16 설치·SAF 승인·제공된 YouTube/릴스 저장·릴스 Flutter 저장 화면 검증을 완료했다. 이후 기본 GLES 렌더링으로 반복한 네이티브 테스트에서 QEMU가 segmentation fault로 종료했다(당시 unit peak 3.5GB, swap 0). [실행 로그](test-results/dispatch-fix/emulator-resource-guard.txt)에 보존한다. 테스트 helper의 `softwareRendering=true`는 Impeller를 끄고 Flutter CPU 렌더링을 요청한다. 이 인자로 실기기 반복 테스트 6개를 통과했으며, 변경 후 Android 16 재실행은 가용 메모리 6GB 조건에 미달해 시작하지 않았다. GLES 충돌이나 과거 데스크톱 정지의 원인을 확정하는 기록은 아니다.

마지막 충돌로 중단된 전용 AVD에는 테스트 UIDT 서비스의 비활성 상태나 부분 작업이 남을 수 있다. 다음 검증 전 **이 작업에서 새로 만든 `Fetchy_API_36`의 Fetchy 테스트 설치만** 초기화·재설치하고 SAF 테스트 루트를 다시 승인한다. 실기기·Mova AVD·사용자 파일에는 이 초기화를 적용하지 않는다. 기본 렌더링의 성공 사례와 CPU 렌더링 테스트 결과를 혼동하지 않는다.
