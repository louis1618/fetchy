#!/usr/bin/env python3
"""Reproduce bundled yt-dlp and Android QuickJS binaries. No runtime server."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache/engine'
QJS_VERSION = '0.17.0'
YTDLP_VERSION = '2026.08.19'
QJS_SHA = '559bc4c420475e55c7ab4510adbc562f55d7524d75e8e89d79ce4bb02f5687d9'
YTDLP_SHA = '1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6'


def fetch(url, path, digest):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        temporary = path.with_suffix('.download')
        urllib.request.urlretrieve(url, temporary)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != digest:
            temporary.unlink()
            raise RuntimeError('Upstream asset checksum mismatch')
        temporary.replace(path)


def main():
    ndk = Path(os.environ.get('FETCHY_NDK', str(Path.home() / 'Android/Sdk/ndk/28.2.13676358')))
    assert (ndk / 'build/cmake/android.toolchain.cmake').exists(), 'Set FETCHY_NDK to Android NDK 28+'
    fetch(f'https://github.com/yt-dlp/yt-dlp/releases/download/{YTDLP_VERSION}/yt-dlp', ROOT / 'android/app/src/main/assets/yt-dlp', YTDLP_SHA)
    archive = CACHE / 'quickjs.tar.gz'
    fetch(f'https://github.com/quickjs-ng/quickjs/archive/refs/tags/v{QJS_VERSION}.tar.gz', archive, QJS_SHA)
    source = CACHE / f'quickjs-{QJS_VERSION}'
    if not source.exists():
        with tarfile.open(archive) as tar:
            tar.extractall(CACHE, filter='data')
    for abi in ('arm64-v8a', 'x86_64'):
        build = CACHE / f'build-{abi}'
        subprocess.run(['cmake', '-S', str(source), '-B', str(build),
            f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
            f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-24', '-DCMAKE_BUILD_TYPE=Release',
            '-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384',
            '-DQJS_ENABLE_INSTALL=OFF', '-DQJS_BUILD_EXAMPLES=OFF'], check=True)
        subprocess.run(['cmake', '--build', str(build), '--target', 'qjs_exe', '-j', '4'], check=True)
        target = ROOT / f'android/app/src/main/jniLibs/{abi}/libfetchyqjs.so'
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(build / 'qjs', target)
    subprocess.run(['python3', str(ROOT / 'scripts/build_webp.py')], check=True)
    print('Bundled engines ready:', YTDLP_VERSION, QJS_VERSION)


if __name__ == '__main__':
    main()
