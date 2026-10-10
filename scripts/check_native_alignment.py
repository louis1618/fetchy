#!/usr/bin/env python3
"""Check ELF LOAD alignment, including Python/FFmpeg's nested library archives."""
import argparse
import io
import json
from pathlib import Path
import struct
import zipfile


def alignment(data):
    if data[:4] != b'\x7fELF':
        return None
    if data[4] != 2 or data[5] != 1:
        raise ValueError('Expected little-endian ELF64')
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    values = []
    for index in range(count):
        row = struct.unpack_from('<IIQQQQQQ', data, offset + size * index)
        if row[0] == 1:
            values.append(row[-1])
    return min(values) if values else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path, nargs='+')
    args = parser.parse_args()
    results = []
    for apk in args.apk:
        tested = 0
        failures = []
        with zipfile.ZipFile(apk) as package:
            for name in package.namelist():
                if not name.startswith('lib/') or not name.endswith('.so'):
                    continue
                data = package.read(name)
                members = [(name, data)]
                if data[:2] == b'PK':
                    with zipfile.ZipFile(io.BytesIO(data)) as nested:
                        members = [(name + ':' + child, nested.read(child)) for child in nested.namelist() if not child.endswith('/')]
                for member, blob in members:
                    value = alignment(blob)
                    if value is None:
                        continue
                    tested += 1
                    if value < 16384:
                        failures.append({'file': member, 'loadAlignment': value})
        results.append({'apk': apk.name, 'elfFiles': tested, 'below16KB': failures})
    print(json.dumps(results, indent=2))
    if any(result['below16KB'] for result in results):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
