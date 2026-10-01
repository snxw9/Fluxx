"""Generate independent hb-shape references explicitly, or compare the native proof to them."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[2]
FONTS = ROOT / 'core-engine/src/main/assets/fonts'
REFERENCE = ROOT / 'core-engine/src/androidTest/assets/text/shaping-reference.json'
CATALOG = {'fluxx.sans': 'Inter-Regular.ttf', 'fluxx.serif': 'NotoSerif-Regular.ttf', 'fluxx.mono': 'JetBrainsMono-Regular.ttf'}
CASES = [('ligature', 'fluxx.serif', 'office'), ('kerning', 'fluxx.sans', 'AV'),
         ('newline', 'fluxx.serif', 'office\nAV gy'), ('missing', 'fluxx.sans', 'A\U0010ffffV'),
         ('combining', 'fluxx.sans', 'A\u0301\u0323'), ('mono', 'fluxx.mono', 'AV office')]


def hash_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command):
    return subprocess.check_output([str(x) for x in command], encoding='utf-8')


def upem(path):
    data = path.read_bytes()
    for i in range(struct.unpack_from('>H', data, 4)[0]):
        tag, _, offset, _ = struct.unpack_from('>4sIII', data, 12 + i * 16)
        if tag == b'head':
            return struct.unpack_from('>H', data, offset + 18)[0]
    raise ValueError('Missing font head table')


def generate(tool):
    version = run([tool, '--version']).splitlines()[0]
    if not version.endswith('14.3.1'):
        raise ValueError(f'Wrong reference version: {version}')
    result = dict(schema=1, reference_version=version, reference_tool_sha256=hash_file(tool),
                  reference_archive='harfbuzz-win64-14.3.1.zip',
                  reference_archive_sha256=hash_file(ROOT / 'tools/text/results/downloads/harfbuzz-win64-14.3.1.zip'),
                  font_sha256={key: hash_file(FONTS / value) for key, value in CATALOG.items()}, fixtures=[])
    input_file = ROOT / 'tools/text/results/reference-line.txt'
    for name, font, text in CASES:
        glyphs, raw, base = [], [], 0
        font_upem = upem(FONTS / CATALOG[font])
        options = ['--font-funcs=ot', f'--font-size={font_upem}', '--direction=ltr', '--script=Latn', '--language=en',
                   '--cluster-level=0', '--utf8-clusters', '--no-glyph-names', '--output-format=json']
        for line_index, line in enumerate(text.split('\n')):
            input_file.write_text(line, encoding='utf-8')
            output = run([tool, *options, '--text-file=' + str(input_file), FONTS / CATALOG[font]])
            parsed = json.loads(output)
            raw.append(parsed)
            for glyph in parsed:
                glyphs.append(dict(glyph, cl=glyph['cl'] + base, line=line_index))
            base += len(line.encode('utf-8')) + 1
        result['fixtures'].append(dict(name=name, font=font, text=text, upem=font_upem, options=options, raw_lines=raw, glyphs=glyphs))
    assert len(result['fixtures'][0]['glyphs']) < len('office'), 'Noto Serif fixture did not ligate'
    REFERENCE.parent.mkdir(parents=True, exist_ok=True)
    REFERENCE.write_text(json.dumps(result, indent=2, ensure_ascii=True) + '\n', encoding='utf-8')
    print(f'Wrote independent reference: {REFERENCE}')


def compare(proof):
    reference = json.loads(REFERENCE.read_text())
    for font, expected in reference['font_sha256'].items():
        assert hash_file(FONTS / CATALOG[font]) == expected, 'Font binary changed'
    rows = []
    for case in reference['fixtures']:
        actual = json.loads(run([proof, FONTS, 'shape', case['font'], case['text'].encode('utf-8').hex()]))
        passed = actual['glyphs'] == case['glyphs'] and actual['upem'] == case['upem']
        rows.append(dict(name=case['name'], passed=passed, actual=actual, expected=case['glyphs']))
        print(case['name'], 'PASS' if passed else 'FAIL')
    output = ROOT / 'tools/text/results/shaping-parity.json'
    output.write_text(json.dumps(dict(passed=all(row['passed'] for row in rows), fixtures=rows), indent=2) + '\n')
    return 0 if all(row['passed'] for row in rows) else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--update-reference', type=Path, metavar='HB_SHAPE_EXE')
    parser.add_argument('--proof', type=Path)
    args = parser.parse_args()
    if args.update_reference:
        generate(args.update_reference)
    if args.proof:
        raise SystemExit(compare(args.proof))
