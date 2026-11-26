"""Reproduce the OFL host-only T5 style-boundary diagnostic input. Never writes production assets."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
COMMIT = 'ffebf8c1ee449e544955a7e813c54f9b73848eac'
BASE = f'https://raw.githubusercontent.com/notofonts/noto-fonts/{COMMIT}'
FONT_SHA = 'ceea25b464a656dc3b26849bab9356740401af62aedf1bfa8b7f0d9b75925b1b'
LICENSE_SHA = '0dab92d0544f7b233403f14b84a663bdbfa746982eda629e7f4f9ffe1b036feb'
FONT_PATH = 'tools/text/fixtures/NotoSansArabic-Regular.ttf'
LICENSE_PATH = 'tools/text/fixtures/NotoSansArabic-Regular-OFL.txt'
ENTRY = 'hinted/ttf/NotoSansArabic/NotoSansArabic-Regular.ttf'


def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'Fluxx-typography-inputs'})
    with urllib.request.urlopen(request, timeout=45) as response:
        data = response.read(16 * 1024 * 1024 + 1)
    if len(data) > 16 * 1024 * 1024:
        raise ValueError('Fixture exceeds 16 MiB')
    return data


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--register-existing', action='store_true', help='Offline: verify the existing fixture and register checksums')
    args = parser.parse_args()
    for path, entry, digest in [(FONT_PATH, ENTRY, FONT_SHA), (LICENSE_PATH, 'LICENSE', LICENSE_SHA)]:
        target = ROOT / path
        data = target.read_bytes() if args.register_existing else fetch(BASE + '/' + entry)
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('Pinned fixture checksum mismatch: ' + path)
        if not args.register_existing:
            target.write_bytes(data)
    manifest_path = ROOT / 'core-engine/third_party/TEXT_INPUTS.json'
    manifest = json.loads(manifest_path.read_text())
    manifest['test_fonts']['noto-arabic-cursive-style'] = dict(repo='notofonts/noto-fonts', commit=COMMIT,
        path=FONT_PATH, sha256=FONT_SHA, license_path=LICENSE_PATH, license_sha256=LICENSE_SHA,
        url=BASE+'/'+ENTRY, license_url=BASE+'/LICENSE')
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    print('Pinned host-only Arabic fixture registered; APK assets unchanged.')
