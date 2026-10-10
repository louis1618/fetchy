#!/usr/bin/env bash
set -euo pipefail
unit=fetchy-emulator-safe.service
sdk=/home/louis/Android/Sdk
script=$(readlink -f "$0")
memory_available() { awk '/^MemAvailable:/ {print $2}' /proc/meminfo; }
memory_pressure() { awk '/^some/ {split($2, a, "="); print int(a[2])}' /proc/pressure/memory; }
case "${1:-status}" in
  start)
    : "${XDG_RUNTIME_DIR:?A logged-in user session is required}"
    if pgrep -f '/emulator/qemu/.*/qemu-system-' >/dev/null; then
      echo 'An emulator is already running. Stop it before starting Fetchy_API_36.' >&2
      exit 75
    fi
    if (( $(memory_available) < 6291456 || $(memory_pressure) >= 5 )); then
      echo 'Insufficient memory headroom or elevated memory pressure. Emulator not started.' >&2
      exit 75
    fi
    /usr/bin/flock --nonblock --conflict-exit-code 75 "$XDG_RUNTIME_DIR/build-safe.lock" /usr/bin/true
    systemd-run --user --unit="$unit" --collect \
      --property=MemoryHigh=infinity --property=MemoryMax=4G --property=MemorySwapMax=0 \
      --property=CPUQuota=150% --property=CPUWeight=10 --property=IOWeight=10 \
      --property=Nice=10 --property=OOMScoreAdjust=500 --property=TimeoutStopSec=20 \
      /usr/bin/flock --nonblock --conflict-exit-code 75 "$XDG_RUNTIME_DIR/build-safe.lock" \
      "$script" run
    ;;
  run)
    "$sdk/emulator/emulator" -avd Fetchy_API_36 -no-window -no-audio \
      -no-boot-anim -no-snapshot -gpu swiftshader -feature -Vulkan -memory 1536 -cores 2 &
    emulator_pid=$!
    trap 'kill "$emulator_pid" 2>/dev/null || true; wait "$emulator_pid" 2>/dev/null || true' EXIT
    pressured=0
    while kill -0 "$emulator_pid" 2>/dev/null; do
      sleep 5
      available=$(memory_available)
      pressure=$(memory_pressure)
      if (( pressure >= 10 )); then pressured=$((pressured + 1)); else pressured=0; fi
      if (( available < 2097152 || pressured >= 2 )); then
        echo "Stopping Fetchy emulator: available=${available}KB memory_pressure=${pressure}%" >&2
        exit 75
      fi
    done
    wait "$emulator_pid"
    ;;
  stop) if systemctl --user is-active --quiet "$unit"; then systemctl --user stop "$unit"; fi ;;
  status)
    if systemctl --user is-active --quiet "$unit"; then
      systemctl --user show "$unit" -p ActiveState -p MemoryCurrent -p MemoryHigh -p MemoryMax -p MemorySwapMax -p CPUQuotaPerSecUSec -p Nice
    else
      echo 'Fetchy emulator is stopped. Resource limits are applied when started.'
    fi
    ;;
  *) echo 'Usage: emulator_safe.sh start|stop|status' >&2; exit 64 ;;
esac
