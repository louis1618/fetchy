#!/usr/bin/env python3
"""Opt-in Android share-to-selection timing; never enqueues or deletes user files."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--url', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--cold-process', action='store_true')
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]

    def shell(*parts):
        return subprocess.run(adb + ['shell', shlex.join(parts)], check=True,
                              capture_output=True, timeout=30).stdout

    if args.cold_process:
        shell('am', 'force-stop', 'com.luverse.fetchy')
    shell('am', 'start', '-a', 'android.settings.SETTINGS')
    performance = []
    marker = 'benchmark_' + uuid.uuid4().hex
    logcat = subprocess.Popen(adb + ['logcat', '-T', '1', '-v', 'brief', '-s', 'FetchyPreview:I', 'FetchyAnalysis:I', 'FetchyDownload:I', 'flutter:I'], stdout=subprocess.PIPE, text=True)
    def collect():
        armed = False
        for line in logcat.stdout:
            if marker in line:
                armed = True
                continue
            if not armed:
                continue
            if any(marker in line for marker in ['FetchyPreview', 'FetchySelection', 'FetchyAnalysis', 'FetchyDownload']):
                performance.append(line.strip())
    collector = threading.Thread(target=collect, daemon=True)
    collector.start()
    shell('log', '-p', 'i', '-t', 'FetchyPreview', marker)
    start = time.monotonic()
    shell('am', 'start', '-n', 'com.luverse.fetchy/.ShareActivity', '-a',
          'android.intent.action.SEND', '-t', 'text/plain', '--es',
          'android.intent.extra.TEXT', args.url)
    result = {'status': 'timeout', 'coldProcess': args.cold_process,
              'note': 'UI polling time includes accessibility dump overhead'}
    try:
        while time.monotonic() - start < 100:
            shell('uiautomator', 'dump', '/sdcard/fetchy-share-timing.xml')
            tree = ET.fromstring(shell('cat', '/sdcard/fetchy-share-timing.xml'))
            labels = [n.get('text', '') + n.get('content-desc', '') for n in tree.iter('node')]
            if any('저장하기' in label and 'Fetchy에' not in label for label in labels):
                result['status'] = 'ready'
                break
            if any(any(error in label for error in ['로그인 상태', '분석에 실패', '원본 미디어가 없', '접근이 제한된']) for label in labels):
                result['status'] = 'unavailable'
                break
    finally:
        logcat.terminate()
        logcat.wait(timeout=5)
        collector.join(timeout=5)
        result['elapsedSeconds'] = round(time.monotonic() - start, 3)
        # Dart elapsed time runs from the actual request, not accessibility polling.
        import re
        for label, pattern in [('previewFrameMs', r'FetchyPreview frame elapsedMs=(\d+)'),
                               ('previewImageMs', r'FetchyPreview image elapsedMs=(\d+)'),
                               ('selectionFrameMs', r'FetchySelection frame elapsedMs=(\d+)')]:
            values = [int(m[1]) for line in performance if (m := re.search(pattern, line))]
            if values:
                result[label] = values[0]
        result['performance'] = performance
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        shell('input', 'keyevent', '4')
        shell('rm', '-f', '/sdcard/fetchy-share-timing.xml')
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
