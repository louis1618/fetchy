#!/usr/bin/env python3
"""Capture native reference surfaces using explicitly seeded test files. Does not create folders or downloads."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--variants', action='store_true', help='Capture welcome, dark and enlarged-type surfaces instead')
    parser.add_argument('--software-rendering', action='store_true', help='Use Flutter CPU rendering for emulator renderer diagnostics')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = ['adb', '-s', args.serial]
    engine_flags = ['--ez', 'enable-software-rendering', 'true'] if args.software_rendering else []
    (args.output / 'render-mode.txt').write_text('Flutter CPU rendering requested via Intent\n' if engine_flags else 'Default Flutter renderer\n')

    def run(*command):
        return subprocess.run(adb + list(command), check=True, capture_output=True).stdout

    def tree():
        run('shell', 'uiautomator', 'dump', '/sdcard/fetchy-design-ui.xml')
        return ET.fromstring(run('shell', 'cat', '/sdcard/fetchy-design-ui.xml'))

    def click(label, exact=False, exclude=None):
        nodes = [node for node in tree().iter('node') if node.get('clickable') == 'true' and
                 ((node.get('content-desc', '') == label or node.get('text', '') == label) if exact else
                  label in (node.get('content-desc', '') + node.get('text', ''))) and
                 (exclude is None or exclude not in (node.get('content-desc', '') + node.get('text', '')))]
        if not nodes:
            raise RuntimeError('Control unavailable: ' + label)
        node = sorted(nodes, key=lambda n: len(n.get('content-desc', '')))[0]
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(.4)

    def wait_for(label):
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            if any(label in (n.get('content-desc', '') + n.get('text', '')) for n in tree().iter('node')):
                return
            time.sleep(.4)
        raise RuntimeError('Screen did not finish loading: ' + label)

    def back():
        run('shell', 'input', 'keyevent', '4')
        time.sleep(.4)

    def capture(name):
        tree()  # Allow semantic/layout updates to settle before asking SurfaceFlinger for pixels.
        time.sleep(1)
        (args.output / (name + '.png')).write_bytes(run('exec-out', 'screencap', '-p'))
        print('CAPTURE', name, flush=True)

    def launch(label):
        run('shell', 'am', 'force-stop', 'com.luverse.fetchy')
        run('shell', 'am', 'start', '-n', 'com.luverse.fetchy/.MainActivity', *engine_flags)
        wait_for(label)

    if args.variants:
        def mode(name):
            result = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                         'com.luverse.fetchy.DesignFixtureDeviceTest#' + name,
                         'com.luverse.fetchy.test/androidx.test.runner.AndroidJUnitRunner')
            if b'OK (1 test)' not in result:
                raise RuntimeError(result.decode())
            (args.output / ('mode-' + name + '.txt')).write_bytes(result)

        original = run('shell', 'settings', 'get', 'system', 'font_scale').decode().strip()
        try:
            mode('welcome'); launch('시작하기'); capture('welcome')
            mode('light')  # Complete onboarding before capturing the main route.
            mode('dark'); launch('최근 링크'); capture('dark-home')
            mode('light')
            run('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
            launch('최근 링크'); capture('font-scale-1.3')
        finally:
            if original == 'null':
                run('shell', 'settings', 'delete', 'system', 'font_scale')
            else:
                run('shell', 'settings', 'put', 'system', 'font_scale', original)
            mode('light')
            run('shell', 'rm', '-f', '/sdcard/fetchy-design-ui.xml')
        return

    launch('최근 링크')
    capture('home')
    click('파일\nTab')
    capture('files')
    click('Instagram', exact=False, exclude='관리')
    capture('folder')
    click('design-fixture-1.jpg', exact=True)
    capture('file-detail')
    click('파일 관리', exact=True)
    capture('file-actions')
    back(); back(); back()
    click('Instagram 폴더 관리', exact=True)
    capture('folder-actions')
    click('하위 폴더 만들기', exact=True)
    capture('create-subfolder')
    back()
    click('설정\nTab')
    capture('settings')
    run('shell', 'am', 'start', '-n', 'com.luverse.fetchy/.ShareActivity', '-a', 'android.intent.action.SEND', '-t', 'text/plain', '--es', 'android.intent.extra.TEXT', 'https://www.w3schools.com/html/mov_bbb.mp4', *engine_flags)
    wait_for('저장하기')
    capture('share-save')
    click('기본 저장 위치 / 자동 분류', exact=True)
    capture('folder-picker')
    back(); back()
    # Cold restore the main route before opening analysis through the URL field.
    launch('최근 링크')
    fields=[n for n in tree().iter('node') if n.get('class')=='android.widget.EditText']
    if not fields:
        raise RuntimeError('URL input unavailable')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',fields[0].get('bounds')))
    run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    run('shell','input','text','https://www.w3schools.com/html/mov_bbb.mp4')
    run('shell','input','keyevent','66')
    wait_for('저장하기')
    # Dismiss keyboard without going back from the selection screen.
    run('shell','input','keyevent','111')
    capture('analysis')
    run('shell','rm','-f','/sdcard/fetchy-design-ui.xml')


if __name__ == '__main__':
    main()
