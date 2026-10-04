"""Create the two-face TTC acceptance fixture offline from pinned outline fonts."""
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]


def collection(fonts):
    offsets, total = [], 12 + 4 * len(fonts)
    for font in fonts:
        offsets.append(total)
        total += (len(font) + 3) & ~3
    result = bytearray(total)
    struct.pack_into('>III', result, 0, 0x74746366, 0x10000, len(fonts))
    for i, (font, offset) in enumerate(zip(fonts, offsets)):
        struct.pack_into('>I', result, 12 + 4 * i, offset)
        data = bytearray(font)
        if len(data) < 12 or data[:4] not in (b'OTTO', b'\x00\x01\x00\x00'):
            raise ValueError('Expected an outline sfnt font')
        count = struct.unpack_from('>H', data, 4)[0]
        if 12 + 16 * count > len(data):
            raise ValueError('Invalid sfnt table directory')
        for table in range(count):
            entry = 12 + 16 * table
            position, length = struct.unpack_from('>II', data, entry + 8)
            if position + length > len(data):
                raise ValueError('Invalid sfnt table bounds')
            struct.pack_into('>I', data, entry + 8, position + offset)
            if data[entry:entry + 4] == b'head':
                struct.pack_into('>I', data, position + 8, 0)
        result[offset:offset + len(data)] = data
    return bytes(result)


if __name__ == '__main__':
    fonts = [(ROOT / 'core-engine/src/main/assets/fonts/Inter-Regular.ttf').read_bytes(),
             (ROOT / 'tools/text/fixtures/SourceSerif4Variable-Roman.otf').read_bytes()]
    output = ROOT / 'tools/text/results/import-collection.ttc'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(collection(fonts))
    print(f'{output}: {output.stat().st_size} bytes, two outline faces')
