#!/usr/bin/env python3
"""Rebuild WebP dependencies for 16KB pages and replace them in the FFmpeg archive."""
from pathlib import Path
import hashlib
import io
import os
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache/engine'
VERSION = '1.6.0'
SOURCE_SHA = '93a852c2b3efafee3723efd4636de855b46f9fe1efddd607e1f42f60fc8f2136'
AAR_SHA = '0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49'


def fetch(url, path, checksum):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
        urllib.request.urlretrieve(url, path)
    if hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
        raise RuntimeError('Engine source checksum mismatch')


def main():
    ndk = Path(os.environ.get('FETCHY_NDK', str(Path.home() / 'Android/Sdk/ndk/28.2.13676358')))
    source_archive = CACHE / 'webp.tar.gz'
    fetch(f'https://github.com/webmproject/libwebp/archive/refs/tags/v{VERSION}.tar.gz', source_archive, SOURCE_SHA)
    source = CACHE / f'libwebp-{VERSION}'
    if not source.exists():
        with tarfile.open(source_archive) as tar:
            tar.extractall(CACHE, filter='data')
    aar_path = CACHE / 'ffmpeg-0.18.1.aar'
    fetch('https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/ffmpeg/0.18.1/ffmpeg-0.18.1.aar', aar_path, AAR_SHA)
    project = CACHE / 'webp-project'
    project.mkdir(exist_ok=True)
    (project / 'CMakeLists.txt').write_text(f'''cmake_minimum_required(VERSION 3.10)
project(FetchyWebP LANGUAGES C)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory("{source}" libwebp)
set_target_properties(sharpyuv webpdecoder webp webpdemux libwebpmux PROPERTIES VERSION "" SOVERSION "")
''')
    targets = ['sharpyuv', 'webpdecoder', 'webp', 'webpdemux', 'libwebpmux']
    off = ['ANIM_UTILS', 'CWEBP', 'DWEBP', 'GIF2WEBP', 'IMG2WEBP', 'VWEBP', 'WEBPINFO', 'WEBPMUX', 'EXTRAS']
    with zipfile.ZipFile(aar_path) as aar:
        for abi in ('arm64-v8a', 'x86_64'):
            build = CACHE / ('webp-' + abi)
            subprocess.run(['cmake', '-S', str(project), '-B', str(build),
                f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
                f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-24', '-DCMAKE_BUILD_TYPE=Release',
                '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384', '-DWEBP_USE_THREAD=ON'] +
                ['-DWEBP_BUILD_' + option + '=OFF' for option in off], check=True)
            subprocess.run(['cmake', '--build', str(build), '--target'] + targets + ['-j', '2'], check=True)
            replacement = {p.name: p for p in (build / 'libwebp').glob('*.so')}
            names = {'libsharpyuv.so', 'libwebpdecoder.so', 'libwebp.so', 'libwebpdemux.so', 'libwebpmux.so'}
            assert names <= replacement.keys(), replacement
            destination = ROOT / f'android/app/src/main/jniLibs/{abi}/libffmpeg.zip.so'
            with zipfile.ZipFile(io.BytesIO(aar.read(f'jni/{abi}/libffmpeg.zip.so'))) as original:
                with zipfile.ZipFile(destination, 'w') as output:
                    for entry in original.infolist():
                        candidate = replacement.get(Path(entry.filename).name)
                        data = candidate.read_bytes() if entry.filename.startswith('usr/lib/') and candidate else original.read(entry)
                        output.writestr(entry, data)
    shutil.copy2(source / 'COPYING', ROOT / 'licenses/libwebp.txt')
    shutil.copy2(source / 'COPYING', ROOT / 'android/app/src/main/assets/licenses/libwebp.txt')
    print('FFmpeg WebP archives rebuilt with 16KB LOAD alignment')


if __name__ == '__main__':
    main()
