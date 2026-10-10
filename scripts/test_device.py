#!/usr/bin/env python3
"""Run opt-in native checks against a dedicated Fetchy test installation."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]
    logs = ROOT / 'docs/test-results' / args.serial
    logs.mkdir(parents=True, exist_ok=True)

    def run(*command):
        return subprocess.run(adb + list(command), check=True, capture_output=True, text=True).stdout

    run('install', '-r', str(args.apk.resolve()))
    run('install', '-r', str(ROOT / 'build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    run('shell', 'mkdir', '-p', '/sdcard/Download/Fetchy')

    def case(name, target):
        output = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.luverse.fetchy.' + target,
                     'com.luverse.fetchy.test/androidx.test.runner.AndroidJUnitRunner')
        (logs / (name + '.txt')).write_text(output)
        if 'OK (' not in output or 'FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output or 'INSTRUMENTATION_STATUS_CODE: -3' in output:
            raise RuntimeError('Failed or skipped device check: ' + name)
        print('PASS', name, flush=True)

    case('storage-grant', 'StorageGrantDeviceTest')
    case('engine-storage-cookies', 'EngineDeviceTest')
    case('queue-controls-concurrency', 'QueueDeviceTest')
    run('shell', 'am', 'force-stop', 'com.luverse.fetchy')
    case('recovery-seed', 'RecoveryDeviceTest#seedInterruptedDownload')
    run('shell', 'am', 'force-stop', 'com.luverse.fetchy')
    case('background-recovery', 'RecoveryDeviceTest#restoreAndFinishInBackground')
    case('external-access', 'ExternalAccessDeviceTest')
    case('fixture-cleanup', 'RecoveryDeviceTest#cleanFixture')


if __name__ == '__main__':
    main()
