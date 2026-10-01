"""Maintain the checked-in, offline text dependency snapshot. Does not build anything."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import struct
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
DOWNLOADS = ROOT / "tools/text/results/downloads"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def vendor(name, archive, prefix, keep):
    target = ROOT / "core-engine/third_party" / name
    target.mkdir(parents=True, exist_ok=True)
    files = {}
    with tarfile.open(DOWNLOADS / archive) as source:
        for entry in source:
            if not entry.isfile():
                continue
            rel = PurePosixPath(entry.name).relative_to(prefix)
            # Upstream ignore files can hide vendored generated headers from Git.
            if '..' in rel.parts or rel.name == '.gitignore' or not keep(rel):
                continue
            data = source.extractfile(entry).read()
            output = target.joinpath(*rel.parts)
            output.parent.mkdir(parents=True, exist_ok=True)
            if not output.exists() or output.read_bytes() != data:
                output.write_bytes(data)
            files[str(rel)] = sha(data)
    provenance = json.loads((DOWNLOADS / (name + '-commit.json')).read_text())
    return dict(provenance, archive=archive, archive_sha256=sha((DOWNLOADS / archive).read_bytes()), files=files)


def font(archive, entry, license_entry, name):
    target = ROOT / "core-engine/src/main/assets/fonts"
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(DOWNLOADS / archive) as source:
        data, license_data = source.read(entry), source.read(license_entry)
    tables = [data[12 + i * 16:16 + i * 16].decode('ascii') for i in range(struct.unpack_from('>H', data, 4)[0])]
    assert 'fvar' not in tables, f"{entry} is variable"
    assert 'glyf' in tables, f"{entry} is not a TrueType outline font"
    (target / (name + '.ttf')).write_bytes(data)
    license_path = ROOT / "core-engine/src/main/assets/licenses" / (name + '-OFL.txt')
    license_path.parent.mkdir(parents=True, exist_ok=True)
    license_path.write_bytes(license_data)
    return dict(archive=archive, archive_sha256=sha((DOWNLOADS / archive).read_bytes()), entry=entry,
                sha256=sha(data), license_entry=license_entry, license_sha256=sha(license_data), tables=tables)


if __name__ == '__main__':
    manifest = {}
    manifest['freetype'] = vendor('freetype', 'freetype-2.14.1.tar.gz', 'freetype-VER-2-14-1',
        lambda p: p.parts[0] in ('src', 'include', 'builds', 'cmake', 'docs') or len(p.parts) == 1)
    manifest['harfbuzz'] = vendor('harfbuzz', 'harfbuzz-14.3.1.tar.gz', 'harfbuzz-14.3.1',
        lambda p: p.parts[0] in ('src', 'util') or len(p.parts) == 1)
    # Exact binary paths are selected from the release archives, never a variable-font instance.
    manifest['fonts'] = {
        'fluxx.sans': font('Inter-4.1.zip', 'extras/ttf/Inter-Regular.ttf', 'LICENSE.txt', 'Inter-Regular'),
        'fluxx.serif': font('NotoSerif-v2.014.zip', 'NotoSerif/unhinted/ttf/NotoSerif-Regular.ttf', 'OFL.txt', 'NotoSerif-Regular'),
        'fluxx.mono': font('JetBrainsMono-2.304.zip', 'fonts/ttf/JetBrainsMono-Regular.ttf', 'OFL.txt', 'JetBrainsMono-Regular')}
    for font_id, repo in [('fluxx.sans', 'inter'), ('fluxx.serif', 'latin-greek-cyrillic'), ('fluxx.mono', 'JetBrainsMono')]:
        manifest['fonts'][font_id].update(json.loads((DOWNLOADS / (repo + '-commit.json')).read_text()))
    destination = ROOT / 'core-engine/third_party/TEXT_INPUTS.json'
    destination.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    for source, output in [('freetype/docs/FTL.TXT', 'FreeType-FTL.txt'), ('harfbuzz/COPYING', 'HarfBuzz-COPYING.txt')]:
        (ROOT / 'core-engine/src/main/assets/licenses' / output).write_bytes((ROOT / 'core-engine/third_party' / source).read_bytes())
    print('Vendored text dependencies, static fonts, hashes and licenses')
