"""Offline verification of vendored typography sources, static fonts and licenses."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def verify():
    manifest = json.loads((ROOT / 'core-engine/third_party/TEXT_INPUTS.json').read_text())
    checked, errors = 0, []

    def check(path, expected):
        nonlocal checked
        checked += 1
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            errors.append(str(path.relative_to(ROOT)))

    for library in ('freetype', 'harfbuzz'):
        for path, digest in manifest[library]['files'].items():
            check(ROOT / 'core-engine/third_party' / library / path, digest)
    for font in manifest['fonts'].values():
        name = Path(font['entry']).name
        check(ROOT / 'core-engine/src/main/assets/fonts' / name, font['sha256'])
        check(ROOT / 'core-engine/src/main/assets/licenses' / (Path(name).stem + '-OFL.txt'), font['license_sha256'])
    for library, source, license_name in [('freetype', 'docs/FTL.TXT', 'FreeType-FTL.txt'), ('harfbuzz', 'COPYING', 'HarfBuzz-COPYING.txt')]:
        check(ROOT / 'core-engine/src/main/assets/licenses' / license_name, manifest[library]['files'][source])
    report = dict(passed=not errors, checked=checked, errors=errors)
    output = ROOT / 'tools/text/results/input-verification.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(verify())
