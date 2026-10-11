#!/usr/bin/env python3
"""获取固定版本 UVC 依赖并校验 SHA-256；第三方文件保留上游许可。"""
import argparse
import hashlib
import os
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVISION = 'cd2a301ff5bd76f70930fccfe4b8e1073edc51f7'
SOURCE_REVISION = 'a2d8fe03665d611d8ff7cd45dd54feed2e19dc56'
BASE = 'https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/' + REVISION + '/'
FILES = (
    ('libuvc/aar/libuvc-3.2.9.aar', 'libuvc-3.2.9.aar',
     '9ef78c44d204fe319b1cde45adec8b7c812a3a42bfbb2a9b263e6df61b1bc940'),
    ('libuvccommon/aar/libuvccommon-3.2.9.aar', 'libuvccommon-3.2.9.aar',
     'f9a3803a397e1c7cd8f9a73b85dd6333bdd7b0276f2a33edb14fc2ce885f2003'),
)


def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            checksum.update(block)
    return checksum.hexdigest()


def download(url, destination, expected=None):
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + '.download')
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'MinimalUvc-dependency-fetch'})
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open('wb') as stream:
            while True:
                block = response.read(65536)
                if not block:
                    break
                stream.write(block)
        if expected and digest(temporary) != expected:
            raise RuntimeError('SHA-256 mismatch: ' + destination.name)
        os.replace(temporary, destination)
    finally:
        if temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='只校验已有 AAR，不下载')
    parser.add_argument('--sources', action='store_true', help='另下载固定提交上游源码归档，不自动解压或执行')
    args = parser.parse_args()
    if args.check and args.sources:
        parser.error('--check 和 --sources 不能同时使用')
    for remote, name, expected in FILES:
        destination = ROOT / 'app' / 'libs' / name
        if destination.is_file() and digest(destination) == expected:
            print('Verified: ' + name)
            continue
        if args.check:
            raise SystemExit('Missing or invalid dependency: ' + name)
        if destination.exists():
            raise SystemExit('Existing dependency differs from upstream: ' + name +
                             '; back it up or remove it explicitly before downloading.')
        download(BASE + remote, destination, expected)
        print('Downloaded and verified: ' + name)
    if args.sources:
        destination = ROOT / 'third_party' / 'source-download' / ('AndroidUSBCamera-' + SOURCE_REVISION + '.tar.gz')
        download('https://codeload.github.com/jiangdongguo/AndroidUSBCamera/tar.gz/' + SOURCE_REVISION,
                 destination)
        print('Upstream source archive: ' + str(destination.relative_to(ROOT)))
        print('SHA-256: ' + digest(destination))
        print('Source revision introducing the 3.2.9 AAR: ' + SOURCE_REVISION)


if __name__ == '__main__':
    main()
