#!/usr/bin/env python3
"""以用户重建的动态库替换 AAR 中 libusb100.so，其他内容原样保留。"""
import argparse
import os
import struct
import zipfile
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('libs', type=Path, help='含 arm64-v8a / armeabi-v7a 子目录的 libs 目录')
    parser.add_argument('--aar', type=Path, default=root / 'app/libs/libuvc-3.2.9.aar')
    args = parser.parse_args()
    replacements = {}
    for abi, elf_class, machine in [('arm64-v8a', 2, 183), ('armeabi-v7a', 1, 40)]:
        path = args.libs / abi / 'libusb100.so'
        data = path.read_bytes()
        if len(data) < 20 or data[:4] != b'\x7fELF' or data[4] != elf_class or data[5] != 1:
            parser.error('Invalid ELF class or endianness: ' + str(path))
        if struct.unpack_from('<H', data, 18)[0] != machine:
            parser.error('Wrong ABI: ' + str(path))
        replacements['jni/' + abi + '/libusb100.so'] = data
    temporary = args.aar.with_suffix('.modified.aar')
    if temporary.exists():
        parser.error('Temporary path already exists: ' + str(temporary))
    try:
        with zipfile.ZipFile(args.aar) as source:
            if not replacements.keys() <= set(source.namelist()):
                parser.error('AAR does not contain the expected libusb entries')
            with zipfile.ZipFile(temporary, 'w') as destination:
                destination.comment = source.comment
                for entry in source.infolist():
                    destination.writestr(entry, replacements.get(entry.filename, source.read(entry)))
        with zipfile.ZipFile(temporary) as check:
            if check.testzip() is not None:
                raise RuntimeError('Modified AAR integrity check failed')
        os.replace(temporary, args.aar)
    finally:
        if temporary.exists():
            temporary.unlink()
    print('Replaced both ARM libusb libraries in ' + str(args.aar))


if __name__ == '__main__':
    main()
