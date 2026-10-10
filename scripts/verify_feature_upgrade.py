#!/usr/bin/env python3
"""Verify the release upgrade using disposable fixtures; run under build-safe to serialize device access."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--adb', default='adb')
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]
    logs = ROOT / 'docs/test-results/feature-upgrade'
    logs.mkdir(parents=True, exist_ok=True)
    test_apk = ROOT / 'build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    with (logs / 'install.txt').open('w') as output:
        for apk in [args.apk.resolve(), test_apk]:
            subprocess.run(adb + ['install', '--no-streaming', '-r', str(apk)], check=True, stdout=output, stderr=subprocess.STDOUT)
    cases = [
        ('physical-features-release.txt', 'com.luverse.fetchy.FeatureUpgradeDeviceTest'),
        ('physical-regression-release.txt', ','.join([
            'com.luverse.fetchy.AutomaticStorageDeviceTest',
            'com.luverse.fetchy.HierarchyDeviceTest',
            'com.luverse.fetchy.QueueDeviceTest',
            'com.luverse.fetchy.QueueStartDeviceTest',
            'com.luverse.fetchy.PreviewDeviceTest#htmlMetadataAndInstagramOriginalsAreNotConfused',
            'com.luverse.fetchy.PreviewDeviceTest#canceledPreviewDoesNotStartNetworking',
            'com.luverse.fetchy.PreviewDeviceTest#storyPhotosAreOriginalsAndNeverVideoPosters',
        ])),
    ]
    for name, classes in cases:
        print('START', name, flush=True)
        command = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', classes,
            '-e', 'mediaUrl', 'https://raw.githubusercontent.com/mdn/learning-area/main/html/multimedia-and-embedding/video-and-audio-content/rabbit320.mp4',
            'com.luverse.fetchy.test/androidx.test.runner.AndroidJUnitRunner']
        with (logs / name).open('w') as output:
            subprocess.run(command, check=True, stdout=output, stderr=subprocess.STDOUT)
        text = (logs / name).read_text()
        if 'OK (' not in text or any(marker in text for marker in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_STATUS_CODE: -3']):
            raise RuntimeError('Failed or skipped device check: ' + name)
        print('PASS', name, flush=True)


if __name__ == '__main__':
    main()
